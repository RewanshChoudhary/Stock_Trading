---
name: Java 25 toolchain
description: Replit runtime selection details for this workspace's Java 25 Maven project.
---

Use a Java 25 JDK explicitly when building the Maven project. Installing Maven can leave its runtime on an older JDK, and the available Nix package set may not include JDK 25.

**Why:** A Java 25 compiler release cannot be built with an older `javac`, even when Maven itself is installed and working.

**How to apply:** Check `java -version` and `mvn -version`; set `JAVA_HOME` and `PATH` to a JDK 25 installation before running Maven tests or the demo.
