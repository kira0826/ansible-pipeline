// /var/lib/jenkins/init.groovy.d/01-set-env-vars.groovy
import jenkins.model.*
import hudson.slaves.EnvironmentVariablesNodeProperty

// Obtener la instancia de Jenkins
def jenkins = Jenkins.getInstance()

// Configurar variables de entorno globales
def globalNodeProperties = jenkins.getGlobalNodeProperties()
def envVarsProperty = globalNodeProperties.get(EnvironmentVariablesNodeProperty)

if (envVarsProperty == null) {
    envVarsProperty = new EnvironmentVariablesNodeProperty()
    globalNodeProperties.add(envVarsProperty)
}

// Definir las variables (modifica estos valores)
def envVars = envVarsProperty.getEnvVars()
envVars.put("REMOTE_HOST", "192.168.1.100")       
envVars.put("REMOTE_USER", "adminuser")       1      
envVars.put("REMOTE_PASSWORD", "Segura$123") 
envVars.put("REMOTE_PATH", "/var/www/html")       

// Guardar la configuración
jenkins.save()