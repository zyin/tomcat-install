import groovy.xml.*

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties
import java.nio.file.StandardCopyOption
import javax.xml.parsers.*

import org.xml.sax.*

// Load properties from config file
def properties = new Properties()
def configFile = 'config.properties'
properties.load(new FileInputStream(configFile))

def tomcatVersion = properties.getProperty('tomcat.version')
def tomcatUrl = properties.getProperty('tomcat.url')
def preDownloadedPackage = properties.getProperty('pre.downloaded.package') // New property for pre-downloaded package
def isWindows = System.getProperty("os.name").toLowerCase().contains("windows")
def httpsPort = properties.getProperty('https.port')

// Check for environment variables
def installDir = System.getenv("TOMCAT_INSTALL_DIR") ?: 
                 (isWindows ? 
                  properties.getProperty('install.dir').replace('/', '\\') : 
                  properties.getProperty('install.dir'))
def tomcatHome = isWindows ? "${installDir}\\apache-tomcat-${tomcatVersion}" : "${installDir}/apache-tomcat-${tomcatVersion}"

def keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: properties.getProperty('keystore.password')

// Command line argument parsing
if (args.length < 2 || !args[0].equals("-certificate")) {
    println "Usage: groovy installTomcat.groovy -certificate [CA|self-signed] [path-to-ca-cert]"
    System.exit(1)
}

def certificateType = args[1]
def caCertPath = (args.length > 2) ? args[2] : null

// Function to check if Tomcat is already installed
def getOldTomcatDir(String installDir) {
    def dir = new File(installDir)

    if (dir.exists() && dir.isDirectory()) {
        def tomcatDirs = dir.listFiles().findAll { file ->
            file.isDirectory() && file.name.toLowerCase().startsWith("apache-tomcat")
        }

        if (tomcatDirs) {
            def foundPath = null
            tomcatDirs.each { tomcatDir ->
                def webappsDir = new File(tomcatDir, "webapps")
                def binDir = new File(tomcatDir, "bin")
                
                if (webappsDir.exists() && webappsDir.isDirectory() && 
                    binDir.exists() && binDir.isDirectory()) {
                    println "Tomcat is installed at: ${tomcatDir.path}"
                    foundPath = tomcatDir.path
                } else {
                    println "Tomcat installation at ${tomcatDir.path} is missing essential directories."
                }
            }
            return foundPath
        } else {
            println "No Tomcat installation found in: ${installDir}"
        }
    } else {
        println "The specified parent directory does not exist: ${installDir}"
    }

    return null
}

// Function to backup configuration files
def backupTomcat(String installDir, String tomcatHome) {
    def backupDir = "${installDir}/backup_${System.currentTimeMillis()}"
    def oldDir = new File(tomcatHome)
    def destDir = new File(backupDir)
    oldDir.renameTo(destDir)
    return backupDir
}

// Function to restore configuration files
def restoreConfigurations(String backupDir, String tomcatHome) {
    ["conf", "webapps", "lib"].each { dir ->
        def srcDir = Paths.get(backupDir, dir)
        def destDir = Paths.get(tomcatHome, dir)
        copyFiles(srcDir.normalize().toString(), destDir.normalize().toString())
    }
    println "Configuration files restored from: ${backupDir}"
}

def copyFiles(String srcDirPath, String destDirPath) {
    def srcDir = new File(srcDirPath)
    def destDir = new File(destDirPath)

    if(srcDir.exists() && srcDir.isDirectory()) {
        srcDir.eachFile { file->
            int i = file.getName().lastIndexOf('.');
            if (i > 0) {
                extension = file.getName().substring(i+1);
                if(extension.equals("orig")) return
            }

            def destFilePath = Paths.get(destDirPath, file.getName())
            if (file.isDirectory()) {
                copyFiles(file.path, destFilePath.toString())
            } else {
                def destFile = new File(destFilePath.normalize().toString())
                destFile.createParentDirectories()
                if (destFile.exists()) {
                    destFile.renameTo(destFilePath.normalize().toString() + ".orig")
                }
                Files.copy(file.toPath(), destFilePath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}

// Function to download Tomcat
def downloadTomcat(boolean isWindows, String tomcatUrl, String targetFile) {
    println "Downloading Tomcat from: ${tomcatUrl}"
    def cmd = isWindows ?
              "curl -o ${targetFile} ${tomcatUrl.replace('.tar.gz', '.zip')}" :
              "wget ${tomcatUrl} -O ${targetFile}"
    def process = cmd.execute()
    process.waitFor()
    if (process.exitValue() != 0) {
        throw new RuntimeException("Failed to download Tomcat: ${process.err.text}")
    }
}

// Function to extract Tomcat
def extractTomcat(boolean isWindows, String sourcePath, String installDir, String tomcatVersion) {
    def cmd = isWindows ?
              "powershell -command \"Expand-Archive -Path ${sourcePath} -DestinationPath ${installDir}\"" :
              "tar xzf ${sourcePath} -C ${installDir}"
    def process = cmd.execute()
    process.waitFor()
    if (process.exitValue() != 0) {
        throw new RuntimeException("Failed to extract Tomcat: ${process.err.text}")
    }

    // Move extracted files to install directory if needed
    if (isWindows) {
        Files.move(Paths.get("${installDir}/apache-tomcat-${tomcatVersion}"),
                   Paths.get(installDir))
    } else {
        new File("${installDir}/apache-tomcat-${tomcatVersion}").renameTo(new File(installDir))
    }
}

// Function to generate a self-signed SSL certificate
def generateSelfSignedCertificate(String tomcatHome, String keystorePassword, String keystoreAlgorithm) {
    def keystorePath = "${tomcatHome}/conf/keystore.jks"
    def command = [
        "keytool", 
        "-genkeypair", 
        "-alias", "tomcat", 
        "-keyalg", keystoreAlgorithm, 
        "-keystore", keystorePath, 
        "-storepass", keystorePassword, 
        "-keypass", keystorePassword, 
        "-dname", "CN=localhost, OU=Development, O=MyCompany, L=MyCity, S=MyState, C=MyCountry", 
        "-validity", "365"
    ]
    println "${command.join(' ')}"
    def process = command.execute()
    process.waitFor()
    if (process.exitValue() != 0) {
        throw new RuntimeException("Failed to generate SSL certificate: ${process.err.text}")
    }
    return keystorePath
}

// Function to configure Tomcat for SSL
def configureSslInServerXml(String tomcatHome, String tomcatVersion, String keystorePath, String keystorePassword, String keystoreAlgorithm, String httpsPort) {
    File keystoreFile = new File(keystorePath)
    println "keystore file name ${keystoreFile.getName()}"

    // load the template by version
    def path = Paths.get("template", "server_${tomcatVersion}.xml")
    String serverXmlData = ""
    Files.readAllLines(path).forEach(line -> serverXmlData += line + "\n")

    // replace the macros in the template file
    serverXmlData = serverXmlData.replace("KEYSTORE_FILENAME", keystoreFile.getName())
    serverXmlData = serverXmlData.replace("KEYSTORE_PASSWORD", keystorePassword)
    serverXmlData = serverXmlData.replace("KEYSTORE_ALGORITHM", keystoreAlgorithm)
    serverXmlData = serverXmlData.replace("HTTPS_PORT", httpsPort)

    def serverXmlPath = "${tomcatHome}/conf/server.xml"
    // save original one
    def serverXmlFile = new File(serverXmlPath)
    if (serverXmlFile.exists()) {
        def serverXmlBackupFile = new File(serverXmlPath + ".orig")
        serverXmlFile.renameTo(serverXmlBackupFile)
    }

    // write the xml to the file
    serverXmlFile = new FileWriter(serverXmlPath)
    serverXmlFile.write(serverXmlData)
    serverXmlFile.close()
}

class Comment extends Node {
    Comment(Object value) {
        super(null, "comment", [:], value)
    }
}

def isTomcatRunning(boolean isWindows) {
    def process
    if (isWindows) {
        process = "tasklist | findstr /I catalina".execute()
    } else {
        process = "ps aux | grep '[c]atalina'".execute()
    }
    process.waitFor()
    return process.exitValue() == 0
}

// Assume the tomcat will be shutdown successfully
def stopTomcat(boolean isWindows, String tomcatHome) {
    if (isWindows) {
        def cmd = "${tomcatHome}\\bin\\shutdown.bat"
        println "Stopping Tomcat using: ${cmd}"
        def process = cmd.execute()
        process.waitFor()
    } else {
        def cmd = "${tomcatHome}/bin/shutdown.sh"
        println "Stopping Tomcat using: ${cmd}"
        def process = cmd.execute()
        process.waitFor()
    }
    println "Tomcat has been stopped."
}

// Function to start Tomcat
def startTomcat(boolean isWindows, String tomcatHome) {
    def cmd = isWindows ? "${tomcatHome}\\bin\\startup.bat" : "${tomcatHome}/bin/startup.sh"
    def process = cmd.execute()
    process.waitFor()
    if (process.exitValue() != 0) {
        throw new RuntimeException("Failed to start Tomcat: ${process.err.text}")
    }
}

// Main execution
try {
    String backupDir = null
    String oldTomcatHome = getOldTomcatDir(installDir)
    if (oldTomcatHome != null) {
        File file = new File(oldTomcatHome)
        def prefixLength = Paths.get(file.getParentFile().getPath(), "apache-tomcat-").toString().length()
        def oldTomcatVersion = oldTomcatHome.substring(prefixLength)
        if (oldTomcatVersion.equals(tomcatVersion)) {
            println "The existing tomcat version is the same as new version ${tomcatVersion}, skip installation"
            return
        }
        if (isTomcatRunning(isWindows)) {
            stopTomcat(isWindows, oldTomcatHome);
        }
        backupDir = backupTomcat(installDir, oldTomcatHome)
    }

    // Use pre-downloaded package if specified
    String sourcePath = preDownloadedPackage ?: "${System.getProperty('user.home')}/Downloads/apache-tomcat-${tomcatVersion}.tar.gz"
    if (!Files.exists(Paths.get(sourcePath))) {
        println "Unable to find pre-downloaded package: ${sourcePath}"
        downloadTomcat(isWindows, tomcatUrl, sourcePath)
    }

    println "Use downloaded package at: ${sourcePath}"
    def localPackageFile = isWindows ? "C:\\temp\\tomcat.zip" : "/tmp/tomcat.tar.gz"
    if (!localPackageFile.equals(sourcePath)) {
        def sourceFile = new File(sourcePath)
        def destinationFile = new File(localPackageFile)
        sourceFile.withInputStream { inputStream ->
            destinationFile.withOutputStream { outputStream ->
                outputStream << inputStream  // Write the input stream to the output stream
            }
        }
    }
    extractTomcat(isWindows, localPackageFile, installDir, tomcatVersion)

    if (backupDir != null) {
        restoreConfigurations(backupDir, tomcatHome)
    } else {
        String keystorePath
        def keystoreAlgorithm = "RSA"
        if (certificateType == "self-signed") {
            keystorePath = generateSelfSignedCertificate(tomcatHome, keystorePassword, keystoreAlgorithm)
        } else if (certificateType == "CA") {
            if (caCertPath == null || !new File(caCertPath).exists()) {
                throw new RuntimeException("Path to CA certificate is required and must exist.")
            }
            keystorePath = "${tomcatHome}/conf/keystore.jks"
            def cmd = "keytool -importkeystore -srckeystore ${caCertPath} -destkeystore ${keystorePath} -srcstoretype PKCS12 -deststoretype JKS -srcstorepass ${keystorePassword} -deststorepass ${keystorePassword}"
            def process = cmd.execute()
            process.waitFor()
            if (process.exitValue() != 0) {
                throw new RuntimeException("Failed to import CA certificate: ${process.err.text}")
            }
        }

        configureSslInServerXml(tomcatHome, tomcatVersion, keystorePath, keystorePassword, keystoreAlgorithm, httpsPort)
    }
    startTomcat(isWindows, tomcatHome)
    println "Tomcat installed and started. Access it at https://localhost:${httpsPort}"

} catch (Exception e) {
    println "An error occurred: ${e.message}"
}

// TODO:
// 1 - add below info to 
/*
*/