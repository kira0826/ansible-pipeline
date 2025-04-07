FROM jenkins/jenkins:lts-jdk17
USER root
RUN apt-get update && \
    apt-get install -y sshpass && \
    rm -rf /var/lib/apt/lists/*
USER jenkins