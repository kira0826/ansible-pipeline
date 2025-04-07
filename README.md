# Project Connections

### Infrastructure Management

The infrastructure is managed using **Terraform** with the **Azurerm** provider. It is organized into three reusable and modular components:

1. **Networking**
2. **Virtual Machines**
3. **GitHub Integration**

This modular approach allows for greater flexibility and reusability of infrastructure components.

The GitHub module is specifically used to dynamically create a webhook between the frontend repository and the Jenkins server. Since the server's public IP changes every time the infrastructure is redeployed, a DNS-based approach was avoided due to cost considerations.

📁 [GitHub – kira0826/terraform_for_each_vm](https://github.com/kira0826/terraform_for_each_vm)

---

### Frontend, Jenkinsfile, and GitHub Actions Workflow

The [Teclado repository](https://github.com/kira0826/Teclado/) contains the entire frontend, which is deployed to a virtual machine using **Nginx**. The deployment is triggered by a **Jenkinsfile** executed by a Jenkins server hosted in a VM. This deployment process is automatically triggered after a **push to the `main` branch**, which can only occur **after a pull request is approved**.

Additionally, the repository includes a **GitHub Actions workflow** that runs whenever a pull request is created. This workflow performs a **code analysis using a SonarQube server**, which is deployed in the same VM as Jenkins. The results of the analysis are posted as comments on the pull request. Approval is **blocked until the analysis comments are available**, enforcing code quality gates.

A **Terraform-created webhook** notifies the Jenkins server whenever a push occurs in the frontend repository.

📁 [GitHub – kira0826/Teclado](https://github.com/kira0826/Teclado/)

---

### Provisioning with Ansible

Two virtual machines are provisioned using **Ansible**:

1. One dedicated to the **frontend deployment** using **Nginx**.
2. One dedicated to **DevOps services**, running **Jenkins** and **SonarQube** in Docker containers.

All services are fully automated for out-of-the-box functionality.

- **Jenkins** is configured using **Configuration as Code (JCasC)** to automatically create plugins and pipeline jobs.
- **SonarQube** setup includes automatic token generation through Ansible. This token is then securely added as a **GitHub secret** in the frontend repository, allowing GitHub Actions to authenticate and run code analysis pipelines.

### Key Ansible Roles:

1. **`devops_services`**
    
    Installs Docker Compose and starts containers for Jenkins, SonarQube, and PostgreSQL (used by SonarQube).
    
2. **`jenkins`**
    
    Automatically installs required Jenkins plugins via `plugins.txt` and sets up jobs using **Groovy scripts and JCasC**.
    
3. **`enable_nginx`**
    
    Prepares static frontend content, configures `nginx.conf` using templates, and exposes the application on port 80.
    
4. **`sonarqube`**
    
    Generates a SonarQube token and stores it as a **GitHub secret** in the frontend repository to enable code analysis during pull requests.
    

https://github.com/kira0826/ansible-pipeline

# Ansible segment:

### Initial Setup

The first step I took was to create a structured Ansible project. In the `inventory/hosts.ini` file, I defined all necessary connections. Then, I developed a set of playbooks to manage the operations across both virtual machines.

---

## DevOps Services Role

This Ansible role is responsible for deploying all containers defined in my `docker-compose.yml` file, including **Jenkins**, **SonarQube**, and the **SonarQube PostgreSQL database**.

One challenge I encountered was that the default Jenkins image did not include `sshpass`, which I needed to perform `scp` file transfers of the frontend build to the VM responsible for serving it. Additionally, I didn't configure any Jenkins agents, and the pipeline is simple enough to run on the controller. However, the container did not support `sudo`, and running as the root user did not allow package installation due to restrictions. To resolve this, I built a **custom Jenkins image** with `sshpass` included:

```docker
FROM jenkins/jenkins:lts-jdk17
USER root
RUN apt-get update && \
    apt-get install -y sshpass && \
    rm -rf /var/lib/apt/lists/*
USER jenkins
```

Once the image was ready, I installed Docker, Docker Compose, and other required packages on the DevOps VM. Then, using Ansible, I copied the `Dockerfile` and `docker-compose.yml` to the VM and started the services:

```yaml
- name: Copy Dockerfile
  copy:
    src: ../Dockerfile
    dest: /home/{{ ansible_user }}/Dockerfile

- name: Copy docker-compose
  copy:
    src: ../docker-compose.yml
    dest: /home/{{ ansible_user }}/docker-compose.yml

- name: Run docker-compose.yml
  command: docker-compose up -d
  args:
    chdir: /home/{{ ansible_user }}/
  become: yes

```

**Service Check (on the VM):**

![image](https://github.com/user-attachments/assets/3bef409d-fe80-457d-8b58-110dd1ddd135)

---

## Nginx Setup Role

The purpose of this Ansible role is to configure and start the **Nginx** service to serve static frontend files. A Jinja2 template stored in the `templates` directory is used to generate an `nginx.conf` file that maps the correct IP and exposes the static files via port **80**.

### Nginx Template:

```
server {
    listen 80;
    server_name 52.191.53.23;

    root /var/www/html;
    index index.html;

    location / {
        try_files $uri $uri/ =404;
    }
}

```

Each time the infrastructure is deployed, a script replaces `server_name` with the updated public IP of the VM to ensure proper exposure of the service.

This configuration is then copied to `/etc/nginx/sites-available/static-site.conf` and symlinked to `sites-enabled` so that Nginx can manage it:

```yaml
- name: Configure Nginx site
  template:
    src: ../templates/nginx-site.conf.j2
    dest: /etc/nginx/sites-available/static-site.conf
  notify:
    - restart nginx

- name: Enable Nginx site
  file:
    src: /etc/nginx/sites-available/static-site.conf
    dest: /etc/nginx/sites-enabled/static-site.conf
    state: link
  notify:
    - restart nginx

```

### Cloning Static Files:

Finally, the static frontend files are cloned into `/var/www/html`, making them accessible through the browser:

```yaml
- name: Clone frontend repository
  git:
    repo: "{{ repo_url }}"
    dest: /var/www/html
    version: main
  become: true

- name: Force update static files
  git:
    repo: "{{ repo_url }}"
    dest: /var/www/html
    version: main
    force: yes
    update: yes

```

**Exposed Service Check:**

![image](https://github.com/user-attachments/assets/1c07d51f-9ce0-4dcb-a4d7-adf7e35cbed8)


---

## Jenkins

In the case of Jenkins, several steps were necessary. The first and most critical one was disabling the manual setup wizard to enable automation more easily. This was achieved by setting the environment variable `JAVA_OPTS=-Djenkins.install.runSetupWizard=false` within the container. This allows steps like setting the admin password or installing plugins to be skipped, so they can be handled later through scripts:

```yaml
  jenkins:
    build: .
    container_name: jenkins
    restart: unless-stopped
    ports:
      - "8080:8080"
      - "50000:50000"
    volumes:
      - jenkins_data:/var/jenkins_home
      - /var/run/docker.sock:/var/run/docker.sock
    environment:
      - JENKINS_ADMIN_ID=admin
      - JENKINS_ADMIN_PASSWORD=admin
      - JAVA_OPTS=-Djenkins.install.runSetupWizard=false

```

Since no plugins are installed by default, we need a scalable way to handle that. This is done using the `jenkins-plugin-cli`, which behaves similarly to Python’s `requirements.txt`—it reads a `.txt` file and installs all listed plugins:

```yaml
- name: Install Jenkins plugins
  command: docker exec -it jenkins jenkins-plugin-cli --plugin-file /plugins.txt

```

### `plugins.txt` content

```
git
ssh-steps
workflow-aggregator
ssh-steps
credentials-binding
github
github-branch-source
configuration-as-code
workflow-cps
workflow-job
job-dsl
docker-workflow
docker-commons

```

---

Next, Jenkins needs access to specific environment variables such as the remote server's password, host URL, and user credentials to perform `scp` operations to the frontend host. These variables are set using a Groovy script executed in the Jenkins console:

```yaml
- name: Check Jenkins status
  command: echo "Check jenkins status"
  notify: verify jenkins

- name: Define environment variables in Jenkins
  jenkins_script:
    url: "http://{{devops_vm_ip}}:8080"
    script: |
      import jenkins.model.*
      import hudson.slaves.EnvironmentVariablesNodeProperty

      println ">>> Starting environment variable configuration"
      println ">>> Executing as: " + jenkins.model.Jenkins.getAuthentication().getName()

      def jenkins = Jenkins.get()
      def globalNodeProperties = jenkins.getGlobalNodeProperties()
      def existing = globalNodeProperties.getAll(EnvironmentVariablesNodeProperty.class)

      existing.each {
        globalNodeProperties.remove(it)
      }

      def envVarsNodeProperty = new EnvironmentVariablesNodeProperty()
      def envVars = envVarsNodeProperty.getEnvVars()

      envVars.put("REMOTE_HOST", "{{ frontend_ip }}")
      envVars.put("REMOTE_USER", "{{ ansible_user }}")
      envVars.put("REMOTE_PASSWORD", '{{ password }}'.toString())
      envVars.put("REMOTE_PATH", "{{ statics_folder }}")

      globalNodeProperties.add(envVarsNodeProperty)
      jenkins.save()
      println "CAMBIOS_REALIZADOS"
      return true
  register: jenkins_script_result
  changed_when: "'CAMBIOS_REALIZADOS' in jenkins_script_result.output"

- name: Display script output
  debug:
    var: jenkins_script_result.output
    verbosity: 0

```

This is equivalent to manually running the script in the Jenkins script console:

![image](https://github.com/user-attachments/assets/5e91c62a-1745-4d0a-8b2b-3df7951ef5bc)


Verification of the created environment variables:

![image](https://github.com/user-attachments/assets/7260f244-8813-45c2-8fbb-75f4b532602a)


---

Finally, to automatically create a pipeline job that reads the `Jenkinsfile` from the repository, we use the **Configuration as Code (JCasC)** plugin. This is done by creating a job definition in YAML format like this:

```yaml
jobs:
  - script: >
      pipelineJob('teclado-pipeline') {
        definition {
          cpsScm {
            scm {
              git {
                remote {
                  url('https://github.com/kira0826/Teclado.git')
                }
                branches('*/main')
              }
            }
            scriptPath('Jenkinsfile')
          }
        }
        triggers {
          githubPush()
        }
      }

```

Jenkins will only load this script if it's located at the path `/var/jenkins_home/jenkins.yaml`. Once copied there, restarting the container will apply the configuration and create the job:

```yaml
- name: Create casc_configs directory inside Jenkins container
  become: true
  command: docker exec jenkins mkdir -p /var/jenkins_home/casc_configs

- name: Copy job template to Jenkins VM
  become: true
  copy:
    src: ../files/jenkins_teclado_repo_job.yml
    dest: /home/{{ ansible_user }}/jenkins_teclado_repo_job.yml

- name: Copy job template to Jenkins container
  become: true
  command: docker cp /home/{{ ansible_user }}/jenkins_teclado_repo_job.yml jenkins:/var/jenkins_home/jenkins.yaml

- name: Set file ownership to Jenkins user
  become: true
  command: docker exec -u 0 jenkins chown jenkins:jenkins /var/jenkins_home/jenkins.yaml

- name: Set correct file permissions
  become: true
  command: docker exec -u 0 jenkins chmod 774 /var/jenkins_home/jenkins.yaml
  notify: restart jenkins

```

Job verification:

![image](https://github.com/user-attachments/assets/b5d957d1-c597-48cc-8c20-733cb82fbe74)


For more examples, refer to the [official Configuration as Code plugin demos](https://github.com/jenkinsci/configuration-as-code-plugin/tree/master/demos).

## SonarQube

For SonarQube, it was necessary to automate a few critical steps due to security constraints introduced in version **9.9 Community Edition**. One of the key requirements is that the default `admin` password **must be changed upon first login**. To handle this automatically, a playbook was created that waits for the service to become available and then uses a POST request to update the password via the SonarQube API:

```yaml
- name: Wait for SonarQube to become available
  uri:
    url: "{{ sonar_host }}/api/system/status"
    method: GET
    return_content: yes
  register: sonar_status
  retries: 20
  delay: 5
  until: sonar_status.status == 200 and '"status":"UP"' in sonar_status.content

- name: Change the default admin password
  uri:
    url: "{{ sonar_host }}/api/users/change_password"
    method: POST
    user: admin
    password: "admin"
    force_basic_auth: yes
    body_format: form-urlencoded
    body:
      login: admin
      previousPassword: "admin"
      password: "{{ new_password }}"
    status_code: [200, 204]
    validate_certs: no
  register: password_change_result

```

This step ensures that all subsequent interactions with the SonarQube API—such as token generation—can be executed securely using the new credentials.

---

Once authenticated, a **SonarQube token** is generated, which is later uploaded to the GitHub repository as a secret. This allows CI pipelines (e.g., GitHub Actions) to use SonarQube for code analysis. In order to upload this token via the `gh` CLI, a GitHub personal access token (PAT) is required:

```yaml
- name: Upload SONAR_TOKEN as a GitHub secret
  shell: |
    gh secret set SONAR_TOKEN -b"{{ sonar_token }}" -R {{ github_repo }}
  environment:
    GH_TOKEN: "{{ github_pat }}"

- name: Verify that SONAR_TOKEN was successfully created
  shell: |
    gh secret list -R {{ github_repo }} | grep SONAR_TOKEN
  environment:
    GH_TOKEN: "{{ github_pat }}"
  register: gh_secret_check
  failed_when: "'SONAR_TOKEN' not in gh_secret_check.stdout"

```

Similarly, the `SONAR_HOST` (which contains the IP or domain of the SonarQube instance) is also stored as a GitHub secret to make it accessible to the pipeline:

```yaml
- name: Set SONAR_HOST as a GitHub secret
  shell: |
    gh secret set SONAR_HOST -b "{{ sonar_host }}" -R {{ github_repo }}
  environment:
    GH_TOKEN: "{{ github_pat }}"

```

This setup ensures secure and seamless integration between SonarQube and GitHub Actions for automated code quality analysis.

### Secrets generated

![image](https://github.com/user-attachments/assets/0b5b0461-6ef9-4553-b76d-c4b124272af8)


### Sonar after a PR

![image](https://github.com/user-attachments/assets/f1e46efc-957d-427a-a130-da4f4b47ef7f)
