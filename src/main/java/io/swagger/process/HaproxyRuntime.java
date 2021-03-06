package io.swagger.process;

import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.FileCopyUtils;

import java.io.File;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;


public class HaproxyRuntime {

    private static Logger logger = org.slf4j.LoggerFactory.getLogger(HaproxyRuntime.class);


    private String appHomePath;

    @Value("${haproxy.config.path}")
    private String haproxyConfigPath;

    @Value("${haproxy.binary.path}")
    private String haproxyBinPath;

    @Value("${haproxy.binary.path}/haproxy.pid")
    private String pidFilePath;

    private Process haproxyProcess;

    public boolean start(){

        logger.debug("start..");
        logger.debug("configFile : {}", haproxyConfigPath);
        try{
            File tempFile = null;//new File(tempPath + filename);
            File haproxyFile = new File(haproxyConfigPath);

            if(!haproxyFile.isFile()){
                haproxyFile.createNewFile();
            }
            FileCopyUtils.copy(tempFile, haproxyFile);
            List<String> argument = new LinkedList<>();


            String pidFilePath = appHomePath+"/haproxy.pid";
            ProcessBuilder processBuilder = new ProcessBuilder(haproxyBinPath, "-p", pidFilePath, "-sf", "$(cat "+pidFilePath+")");
            processBuilder.inheritIO();
            processBuilder.start();
        } catch(IOException e){
            logger.debug("process start fail : {}", e.getMessage());
            return false;
        }
        return true;
    }

    public boolean restart(File tempFile) throws Exception {
        File haproxyFile = new File(haproxyConfigPath);

        if(!haproxyFile.isFile()){
            haproxyFile.createNewFile();
        }
        FileCopyUtils.copy(tempFile, haproxyFile);


        ProcessBuilder processBuilder = new ProcessBuilder(haproxyBinPath, "-p", pidFilePath, "-sf", "$(cat "+pidFilePath+")");
        processBuilder.inheritIO();
        haproxyProcess = processBuilder.start();

        return true;

    }

    public boolean stop() {
        haproxyProcess.destroy();
        return true;
    }

    public int validCheck(File configFile)throws IOException{
        String configPath = configFile.getPath();
        ProcessBuilder processBuilder = new ProcessBuilder("haproxyBinPath -c -f " + configFile.getPath());
        processBuilder.inheritIO();
        return processBuilder.start().exitValue();
    }
}
