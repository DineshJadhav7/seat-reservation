BASE_URL ?= http://localhost:8000
ADMIN_TOKEN ?= admin-secret

build:
	mvn -B -DskipTests package

up:
	docker compose up --build

burst:
	java scripts/Burst.java $(BASE_URL) --admin-token $(ADMIN_TOKEN)

# builds nothing: expects target/app.jar and a Postgres reachable through DATABASE_URL
verify:
	bash scripts/verify.sh
