# LiA - Licensing Assistant for IntelliJ IDEA

LiA is an IntelliJ IDEA plugin that helps developers inspect project licenses, dependency licenses, SBOM data, and licensing compatibility issues directly inside the IDE.

## Prerequisites

- Java 21
- IntelliJ IDEA Community or Ultimate
- Apache Maven available on `PATH` (`mvn -v` must work)
- One configured LLM backend:
  - OpenAI-compatible API access and API key, or
  - Ollama with a reachable host

Optional:
- Gradle installed locally. It is not required if you use the Gradle wrapper (`./gradlew`).

## Project Setup

```sh
git clone https://github.com/TStalnaker44/LiA-user-evaluation.git
cd licensing_tool
```

## Usage Mode 1: Install the Plugin from the ZIP

An installable plugin ZIP is already available in:

- `/Users/danielebifolco/IdeaProjects/licensing_tool/build/distributions/`

Install it in IntelliJ IDEA:

1. Open IntelliJ IDEA
2. Go to `Settings/Preferences > Plugins`
3. Click the gear icon
4. Select `Install Plugin from Disk...`
5. Choose the ZIP file from `build/distributions`
6. Restart IntelliJ IDEA if requested

After installation:

1. Open a Maven project
2. Open the `LiA` tool window from the right-side stripe
3. Open `Settings` and configure the selected model provider
   - OpenAI models require a valid API key
   - Ollama models require a reachable Ollama host
4. Complete the `Licensing Configuration`
5. Optionally use `Help Assign License` if the project is currently unlicensed or the intended license is still undecided

### Building the zip folder

If you need to rebuild the zip folder, run the following command:

```sh
bash ./gradlew buildPlugin
```

The generated archive can be found at:

- `/build/distributions/licensetool-x.x.zip`

## Usage Mode 2: Run the Plugin in a Sandbox

Run a sandboxed IntelliJ instance with the plugin installed:

```sh
bash ./gradlew runIde
```

This starts a separate IntelliJ sandbox based on IntelliJ IDEA Community 2025.1, with the plugin loaded from the current source tree.

Recommended validation flow in the sandbox:

1. Open a Maven project
2. Open the `LiA` tool window
3. Configure a model in `Settings`
   - OpenAI models require a valid API key
   - Ollama models require a reachable Ollama host
4. Complete the `Licensing Configuration`
5. Interact with the assistant in chat
6. Use `Help Assign License` to ask LiA for license recommendations based on:
   - the current dependency set
   - the answers in the Licensing Configuration
7. Modify `pom.xml` to trigger SBOM regeneration and dependency compliance checks

## Runtime Artifacts

When LiA runs on a target project, it creates a `.license-tool` directory in that target repository. This directory is used for generated analysis artifacts and logs, including:

- SBOM-related files
- Maven dependency tree output
- dependency summaries
- licensing configuration data
- session data
- logs under `.license-tool/log`

Typical contents include:

- `.license-tool/bom.xml`
- `.license-tool/dependency-summary.json`
- `.license-tool/dependency-tree.txt`
- `.license-tool/license-survey.json`
- `.license-tool/chat-sessions/`
- `.license-tool/log/interaction-log.jsonl`
- `.license-tool/log/usage.log`

## Notes

- The plugin currently targets IntelliJ IDEA Community `2025.1`
- The project is built with Java 21
- The plugin analyzes Maven-based projects
- The chat UI supports structured reports, dependency trees, and Markdown-style tables
- Automatic compliance checks depend on the Licensing Configuration being present

## License Information

The software in this repository is licensed under the [GNU General Public License v3.0 (GPL-3.0)](/Users/danielebifolco/IdeaProjects/licensing_tool/LICENSE).

The license compatibility matrix used by the plugin is stored in:

- `/Users/danielebifolco/IdeaProjects/licensing_tool/src/main/resources/license_data/matrix.csv`

That matrix is provided by the [Open Source Automation Development Lab (OSADL) eG](https://www.osadl.org/?id=3115) and is licensed under the [Creative Commons Attribution 4.0 International license (CC-BY-4.0)](https://creativecommons.org/licenses/by/4.0/).
