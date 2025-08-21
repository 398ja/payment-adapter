# Repository guidelines

This codebase is a multi‑module Maven project built with Java 21.  
Modules include:

- `cashu-gateway-model` – JPA entities
- `cashu-gateway-rest` – Spring Boot REST API
- `cashu-gateway-client` – REST client
- `cashu-gateway-phoenixd` – phoenixd integration
- `cashu-gateway-webhook` – servlet webhook handler
- `cashu-gateway-dummy` – mock gateway implementation
- `cashu-gateway-common` – shared interfaces

## Coding style
- Use **four spaces** for indentation.
- Keep braces on the same line as the declaration.
- Remove unused imports and avoid trailing whitespace.

## Building & testing
- Requires Java 21+ and Maven 3.8+.
- Run `mvn test` from the repository root before every commit.
    - To run tests for a single module: `mvn -pl <module> test`.
- New or modified code should include unit tests in `src/test/java`.
- Ensure `mvn package` completes without errors.
- Add a comment on top of every test method to describe the test in plain English.

## Docker
- If Dockerfiles or `docker-compose.yml` are changed, verify the images build by running `docker-compose build`.

## Commits
- Use concise, descriptive commit messages (e.g. “Add payment update endpoint”).
- Keep commit history clean and focused on a single change.

## Pull requests
- Use the provided PR template for all pull requests. 
- Always follow the repository's PR submission guidelines and use the PR template located at `.github/pull_request_template.md`.

