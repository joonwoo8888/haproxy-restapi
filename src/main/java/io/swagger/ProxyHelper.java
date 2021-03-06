package io.swagger;

import io.swagger.model.ACL;
import io.swagger.model.Backend;
import io.swagger.model.Frontend;
import io.swagger.model.Service;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.FileCopyUtils;

import java.io.*;
import java.security.acl.Acl;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Created by swsong on 17. 8. 30..
 */
@Component
public class ProxyHelper {

    private org.slf4j.Logger logger = LoggerFactory.getLogger(ProxyHelper.class);

    private Random random = new Random(System.nanoTime());
    private String tempFilePath = System.getProperty("java.io.tmpdir");
    private String TEMPLATE_NAME = "templates/haproxy.cfg.vm";

    // private String appHome           = "/";
    private String haproxyBinaryPath = "/usr/sbin/haproxy";
    private String haproxyConfigPath = "/etc/haproxy/haproxy.cfg";
    private String pidFilePath       = "/run/haproxy.pid";

    private Process process;

    private final ReadWriteLock lock;
    private final Lock writeLock;

    private VelocityEngine engine;

    public ProxyHelper() {
        engine = new VelocityEngine();
        engine.setProperty(RuntimeConstants.RESOURCE_LOADER, "classpath");
        engine.setProperty("classpath.resource.loader.class", ClasspathResourceLoader.class.getName());
        engine.init();

        lock = new ReentrantReadWriteLock();
        writeLock = lock.writeLock();
    }

    protected String renderTemplate(Map<String, Service> config) {
        VelocityContext context = new VelocityContext();
        Map<String, Frontend> frontendUniqueMap = new HashMap<>();
        List<Frontend> frontends = new ArrayList<>();
        List<Backend> backends = new ArrayList<>();
        context.put("frontends", frontends);
        context.put("backends", backends);

        Iterator<Map.Entry<String, Service>> iter = config.entrySet().iterator();

        while(iter.hasNext()) {
            Map.Entry<String, Service> e = iter.next();
            String id = e.getKey();
            Service service = e.getValue();

            Integer bindPort = service.getBindPort();
            String mode = service.getMode();
            String subdomain = service.getSubdomain();
            Integer timeout = service.getTimeout();
            String host = service.getHost();
            Integer port = service.getPort();

            if(bindPort == null){
                throw new ConfigInvalidException("bindPort cannot be empty.");
            } else if(host == null) {
                throw new ConfigInvalidException("host cannot be empty.");
            } else if(port == null) {
                throw new ConfigInvalidException("port cannot be empty.");
            }

            String feName = makeName(mode, bindPort);
            String beName = makeName(host, port);
            Frontend old = frontendUniqueMap.get(feName);

            logger.debug("feName : {}, old : {}", feName, old);
            //HTTP_80 가 이미존재하면 frontend를 만들지 않고, 가져와서 acl만 추가.
            if(old != null && "http".equalsIgnoreCase(mode)){
                Map<String, ACL> aclMap = old.getAclsNotNull();
                if(aclMap.containsKey(subdomain)) {
                    //reject.
                    throw new ConfigInvalidException("Frontend acl duplicated. subdomain = " + subdomain);
                }
                ACL acl = createACL(beName, subdomain);
                aclMap.put(subdomain, acl);
            } else {
                if (old != null && "tcp".equalsIgnoreCase(mode)) {
                    logger.warn("Request frontend {} for tcp override! old = ip[{}] port[{}] defBackend[{}]", feName, old.getBindIp(), old.getBindPort(), old.getDefaultBackend());
                }
                //존재하지 않거나,tcp의 경우.
                Frontend fe = new Frontend();
                fe.setName(feName);
                fe.setBindIp("*");
                fe.setBindPort(bindPort);
                fe.setMode(mode);
                fe.setTimeoutClient(timeout);
                fe.setDefaultBackend(beName);

                if ("http".equalsIgnoreCase(mode)) {
                    ACL acl = createACL(beName, subdomain);
                    fe.getAclsNotNull().put(subdomain, acl);
                }
                frontends.add(fe);
                frontendUniqueMap.put(feName, fe);
            }

            boolean BackEndUniq = true;
            for(Backend b : backends) {
                //이름이 동일한 backend가 있다면 무시.
                if(b.getName().equals(beName)) {
                    BackEndUniq = false;
                    break;
                }
            }
            if(BackEndUniq) {
                Backend be = new Backend();
                be.setName(beName);
                be.setMode(mode);
                be.setHost(host);
                be.setPort(port);
                be.setTimeoutServer(timeout);
                be.setTimeoutConnect(5000); //5000ms
                backends.add(be);
            }

        }

        org.apache.velocity.Template template = engine.getTemplate(TEMPLATE_NAME, "utf-8");
        StringWriter stringWriter = new StringWriter();

        template.merge(context, stringWriter);
        String configString = stringWriter.toString();

        return configString;
    }

    private ACL createACL(String name, String subdomain){
        ACL acl = new ACL();
        acl.setBackend(name);

        if (subdomain == null || subdomain.equals("")) {
            subdomain = "_";
            //서브도메인이 없으면 패턴도 없다. 즉, default_backend로 처리.
        } else {
            acl.setName(subdomain);
            acl.setPattern("hdr_beg(host) " + subdomain + ".");
        }
        return acl;
    }

    private String makeName(String mode, int port) {
        return mode+"_"+port;
    }

    public String applyConfig(Map<String, Service> config) throws ConfigInvalidException {
        writeLock.lock();

        try {
            String tempFileName = Long.toString(Math.abs(random.nextLong()));
            File tempFile = new File(tempFilePath, tempFileName);

            //1. 임시 저장
            String configString = renderTemplate(config);
            logger.info("applyConfig => {}", configString);
            Writer fileWriter = new OutputStreamWriter(new FileOutputStream(tempFile));
            fileWriter.write(configString);
            fileWriter.close();


            //2. validate
            Process process = new ProcessBuilder(haproxyBinaryPath
                                        , "-c"
                                        , "-f"
                                        , tempFile.getPath())
                                        .inheritIO().start();
            process.waitFor();
            if(process.exitValue() != 0){
                throw new Exception("haproxy.cfg is invalid! >> \n" + configString);
            }


            //3. 덮어쓰기.
            File configFile = new File(haproxyConfigPath);
            FileCopyUtils.copy(tempFile, configFile);


            //4. PID정보 수집
            String pid = "";
            File pidFile = new File(pidFilePath);
            if(pidFile.isFile()){
                BufferedReader bufferedReader = new BufferedReader(new FileReader(pidFile));
                String tmp = "";
                while( (tmp = bufferedReader.readLine()) != null){
                    pid += tmp + " ";
                }
                bufferedReader.close();
            }

            //5. restart -sf
            restartProxy(pid);

            return configString;

        } catch (Exception e) {
            throw new ConfigInvalidException(e);
        } finally {
            writeLock.unlock();
        }
    }

    public void restartProxy(String pid) throws IOException {

        ProcessBuilder processBuilder = null;

        if(pid != null && pid.length() > 0) {
            processBuilder = new ProcessBuilder(haproxyBinaryPath
                    , "-f", haproxyConfigPath
                    , "-p", pidFilePath
                    , "-sf", pid)
                    .inheritIO();
        } else {
            processBuilder = new ProcessBuilder(haproxyBinaryPath
                    , "-f", haproxyConfigPath
                    , "-p", pidFilePath)
                    .inheritIO();
        }

        logger.info("processBuilder => {}", processBuilder.command());
        this.process = processBuilder.start();
        logger.info("haproxy update ok!. prevPid[{}]", pid);
    }

    public void stopProxy() {
        process.destroy();
    }

}
