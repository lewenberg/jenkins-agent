# AI task runner for Jenkins (Claude Opus on Bedrock)

Parameters `TEAM`, `ORG`, `APP` and a multiline `TASK`. Four Claude agents on Amazon
Bedrock take the task through a cycle inside the build. There's no server and no UI.

```
TASK ─▶ Builder ─▶ Validator ─▶ Runner ─▶ Output validator ─▶ pass: done
           ▲          │ fail       │ fail         │ fail
           └──────────┴────────────┴──────────────┘  (next cycle, up to MAX_CYCLES)
```

| Agent | Job | Tools |
|---|---|---|
| Builder | Makes the changes the task asks for. Reports every file changed and why, and the commands to run | read tools, `write_file`, `revert_file` |
| Validator | Fresh conversation, its own prompt. Checks the changes against the task: versions, configs, the reason for each change, bugs. Fixes small mistakes and reverts bad changes itself (`fixed`), or sends it back (`fail`) | read tools, `write_file`, `revert_file` |
| Runner | Runs it in the shell (build, test, push), doesn't edit. Errors go back to the builder | read tools, `run_shell` |
| Output validator | Checks the run's output text against the task. Can question the builder, validator or runner with `ask_agent` (they answer read-only, in a fresh turn) | read tools, `ask_agent` |

Each agent ends its turn by calling `finish` with a status and a report, which is how
the Jenkinsfile decides where to go next. A failed check's findings go to the builder in
the next cycle.

The console log shows each agent's stages, replies, tool calls and results. The build
archives `ai-result.md` (every cycle's reports, with the model and `PROMPT_VERSION`) and
`ai-output.txt` (every command the runner ran and its output). The build fails if no
cycle passes.

## Files

| Path | What it is |
|---|---|
| `Jenkinsfile` | Parameters, Bedrock credentials, the four agents' prompts and tools, the cycle, and the agent loop |
| `jenkins/aiTools.groovy` | Tool specs sent to the model and their Groovy implementations |

## Tools

| Tool | Does |
|---|---|
| `get_build_info` | Job, build, agent, workspace, git branch/commit, non-secret env vars |
| `list_files` | Recursive listing of the workspace |
| `read_file` | Read a file (by line range) |
| `search_func` | Find what already exists, in three places: function definitions in the workspace; the company's shared-library steps (`vars/`, with help text and `call()` signatures) and `src/` classes; and this Jenkins's agents, labels and tool installations such as `node22`, JDKs, Maven and Gradle |
| `search_files` | grep across the workspace |
| `run_shell` | Run bash in the workspace (exit code + output) |
| `write_file` | Create or overwrite a file |
| `show_changes` | git status, diff and new files, plus the files the agents wrote |
| `revert_file` | Restore a file from the last commit, or delete it if new |

Read tools are `get_build_info`, `list_files`, `read_file`, `search_func`,
`search_files` and `show_changes`. To add a tool, add a spec to `definitions()` and a
`case` to `run()`, then add its name to the agents that should have it in `agents()` in
the Jenkinsfile.

### Where `search_func` looks

- **Shared libraries:** Jenkins checks out every library a build loads into
  `<workspace>@libs/`. Add your company's `@Library('...') _` line to the Jenkinsfile,
  or mark the libraries "load implicitly" in Jenkins, so they're there to search.
- **Agents, labels and tools:** read from the controller's `$JENKINS_HOME`
  (`nodes/*/config.xml`, `config.xml`, and the tool XML files such as
  `jenkins.plugins.nodejs.tools.NodeJSInstallation.xml`). That only works when the build
  runs on the controller (built-in node). On an agent, the tool says it can't see them
  and suggests trying the name directly. It never reads `credentials.xml` or `secrets/`.
- When the build runs on the controller, `run_shell`, `write_file` and `revert_file` are turned off,
  because the controller's disk holds every Jenkins secret.

The model can run any shell command on the agent with the build's permissions. Run
this job on an agent you're fine with that on, and restrict who can start it.

## Set up

1. Plugins: Pipeline, Pipeline Utility Steps, CloudBees AWS Credentials. The agent
   needs `sh` and AWS CLI v2.
2. Add a Jenkins credential `bedrock-aws` of kind "AWS Credentials" that can call
   `bedrock:InvokeModel` on the model.
3. Turn on model access for Claude Opus in the Bedrock console.
4. Check the settings at the top of the `Jenkinsfile`: `AI_MODEL`, `AI_REGION`,
   `CREDENTIALS_ID`, `MAX_CYCLES`, `MAX_STEPS`, `MAX_TOKENS`. Bump `PROMPT_VERSION`
   when you change an agent's prompt. If your region serves Opus through a
   cross-region inference profile, set `AI_MODEL` to that profile's ID
   (`aws bedrock list-inference-profiles`).
5. Create a Pipeline job from this `Jenkinsfile`. The first run only registers the
   parameters. After that, use "Build with Parameters" and type the task.
