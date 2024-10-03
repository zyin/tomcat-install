Apache Tomcat Installation and Configuration Guide
# Prerequisites

Java Development Kit (JDK): Ensure JDK 8 or later is installed. You can verify this by running java -version in your terminal.

# Download Tomcat

* Visit the Apache Tomcat website.
* Navigate to the "Download" section.
* Choose the desired version (e.g., Tomcat 9 or Tomcat 10).
* Download the binary distribution (usually a .zip or .tar.gz file).

# Extract Tomcat

Open your terminal or command prompt.
Navigate to the directory where you downloaded Tomcat.
Extract the downloaded file:

| ZIP | tar.gz |
| ------- | ------ |
| unzip apache-tomcat-<version>.zip | tar -xvzf apache-tomcat-<version>.tar.gz |

# Set Environment Variables

JAVA_HOME: Set the Java home path in your environment variables.
* For Windows:
  * Right-click on "My Computer" > Properties > Advanced System Settings > Environment Variables.
  * Add a new variable JAVA_HOME with the path to your JDK.
* For Linux/macOS, add the following line to your .bashrc or .bash_profile:
  * export JAVA_HOME=/path/to/your/jdk

Then run source ~/.bashrc.

<B>CATALINA_HOME</B>: Set this to the Tomcat installation directory.

| Windows | Linux |
| ------- | ------ |
| Similar to JAVA_HOME | export CATALINA_HOME=/path/to/tomcat | 
| Add a new variable CATALINA_HOME with the Tomcat path. | |

# Stop Tomcat

If the tomcat is running, you need to backup the server.xml and stop Tomcat first.

To stop Tomcat, run command as below:

| Windows | Linux |
| ------- | ------ |
| shutdown.bat | ./shutdown.sh |

# Start Tomcat

Navigate to the bin directory of the Tomcat installation.
| Windows | Linux |
| ------- | ------ |
| cd %CATALINA_HOME%\bin | cd $CATALINA_HOME/bin |
| startup.bat | ./startup.sh |

Verify that Tomcat is running by opening a web browser and navigating to http://localhost:8080. You should see the Tomcat welcome page.

# Configure Tomcat

Change the Default Port: Edit server.xml located in the conf directory:

```xml
<Connector port="8080"
            protocol="HTTP/1.1"
            connectionTimeout="20000"
            redirectPort="8443" />
```

Change 8080 to your desired port.

Deploy Applications: Place your .war files in the webapps directory for deployment.

User Management: To manage users and roles, edit the tomcat-users.xml file in the conf directory.

# Configure SSL
Ensure you have a valid SSL certificate. You can obtain one from a Certificate Authority (CA) or create a self-signed certificate for testing purposes.

## Create a Keystore
Tomcat uses a keystore to store SSL certificates. You can create a self-signed certificate using the Java keytool command.

## Create a Self-Signed Certificate

Open your terminal or command prompt.
Run the following command, replacing the placeholder values with your information:

```bash
keytool -genkey -alias tomcat -keyalg RSA -keystore /path/to/keystore.jks -keysize 2048
```
You will be prompted to enter information for the certificate (name, organization, etc.) and a password for the keystore.

## Configure Tomcat for SSL

Navigate to the Tomcat conf directory and open the server.xml file.
Find the
```xml
<Connector>
```
element for the HTTPS port. It may be commented out by default. If not present, you can add a new connector entry similar to the following:

```xml
<Connector port="8443" 
            protocol="org.apache.coyote.http11.Http11NioProtocol"
            maxThreads="150" 
            SSLEnabled="true" 
            scheme="https" 
            secure="true" 
            clientAuth="false" 
            sslProtocol="TLS" 
            keystoreFile="/path/to/keystore.jks" 
            keystorePass="your_keystore_password" />
```
port: Set the port you want to use for HTTPS (default is 8443).
keystoreFile: Specify the path to your keystore file.
keystorePass: Enter the password you set when creating the keystore.

## Restart Tomcat

After saving the changes to server.xml, restart Tomcat to apply the new configuration.
Restart Commands

| Windows | Linux |
| ------- | ------ |
| cd %CATALINA_HOME%\bin | cd $CATALINA_HOME/bin |
| shutdown.bat | ./shutdown.sh |
| startup.bat | ./startup.sh |

## Test the SSL Configuration

Open a web browser.
Navigate to https://localhost:8443.
If you used a self-signed certificate, your browser may show a warning. You can proceed after accepting the risk.

## Redirect HTTP to HTTPS

To ensure all traffic uses HTTPS, you can add a redirect in your server.xml:

Find the HTTP connector (usually on port 8080) and add a redirectPort attribute:

```xml
<Connector port="8080" 
            protocol="HTTP/1.1"
            connectionTimeout="20000"
            redirectPort="8443" />
```

This will automatically redirect any HTTP traffic to HTTPS on port 8443.
