ENV ?= stage
TAG ?= $(shell git rev-parse --short HEAD 2>/dev/null || echo dev)
REPO ?= AraPersonal/PTunnel

.PHONY: install test lint infra-check drift dev publish-init

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

# First landing on GitHub. Destructive on the remote by design (the remote holds one README commit),
# so it refuses to run unless the tree is clean, the secret scan passes, and the operator confirms.
publish-init:
	@bash tools/check-no-secrets.sh
	@test -z "$$(git status --porcelain)" || { echo "working tree is dirty - commit first"; exit 1; }
	@echo "The repository must be PRIVATE before this push:"
	@echo "  GitHub -> Settings -> General -> Danger Zone -> Change visibility -> Private"
	@printf "Type 'force-main' to replace origin/main with this history: "; read -r answer; \
	  [ "$$answer" = "force-main" ] || { echo "aborted"; exit 1; }
	git push --force-with-lease origin main
	@echo "pushed. next: enable branch protection, create the stage/prod environments, then run deploy."
