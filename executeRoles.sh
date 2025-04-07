#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR" || exit 1

# a. Enable docker-compose and start containers.
ansible-playbook -i inventory/hosts.ini playbooks/devops_services.yml

# b. Enable web service with nginx.
ansible-playbook -i inventory/hosts.ini playbooks/enable_nginx.yml

# c. Set up jenkins and sonarqube.
ansible-playbook -i inventory/hosts.ini playbooks/set_up_devops_vm.yml


