ENV ?= stage
TAG ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
REPO ?= owner/ptunnel-core
.PHONY: install test lint infra-check drift dev
install:
	cd apps/panel && npm ci --no-audit --no-fund
test:
	cd apps/panel && npm test
	bash tools/check-no-secrets.sh
lint:
	bash tools/check-no-secrets.sh
infra-check:
	ANSIBLE_CONFIG=infra/ansible/ansible.cfg ansible-playbook -i infra/ansible/inventories/$(ENV)/hosts.yml infra/ansible/playbooks/site.yml --syntax-check
drift:
	ANSIBLE_CONFIG=infra/ansible/ansible.cfg ansible-playbook -i infra/ansible/inventories/$(ENV)/hosts.yml infra/ansible/playbooks/site.yml --check --diff
dev:
	cd apps/panel && PT_ENV=dev PT_PORT=8080 node src/server.js
