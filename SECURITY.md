# Security Policy

## Supported versions

Only the latest commit on `main` is supported. There are no release branches.

## Reporting a vulnerability

Please do **not** open a public issue for security problems.

Report privately through GitHub: open the **Security** tab of this repository and choose **Report a vulnerability**. Include:

- what you found and where (file, function, request),
- steps or a minimal payload to reproduce it,
- the impact you expect.

You should get an acknowledgement within 5 working days. Confirmed issues are fixed on `main` with a regression test, and the reporter is credited unless they ask otherwise.

## Scope

Milkrun is a research prototype using synthetic orders and vehicles. The broker, outbox and services run in one JVM with no persistence or authentication. `./build.sh serve` starts the control tower HTTP server bound to all network interfaces with no authentication: run it only on a trusted machine or network.
