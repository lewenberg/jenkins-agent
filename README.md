# AI task runner for Jenkins (Claude Opus on Bedrock)

One Jenkins parameter, `TASK` (multiline). The build sends it to Claude Opus on Amazon
Bedrock, and the model carries it out in the job's workspace using the tools in
`jenkins/aiTools.groovy`. There's no server and no UI. Everything happens inside the
build:

```
TASK ──▶ Jenkinsfile loop ──aws bedrock-runtime converse──▶ Bedrock
              ▲                                                 │
              └──── tool result ◀── aiTools.groovy ◀── tool call┘
```

The console log shows every model reply, tool call and result. The final answer is
archived as `ai-result.md`.

## Files

| Path | What it is |
|---|---|
| `Jenkinsfile` | The `TASK` parameter, Bedrock credentials, and the agent loop |
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

The model gets these specs with every request, and the system prompt tells it they
run on the Jenkins agent. To add a tool, add a spec to `definitions()` and a `case` to
`run()`.

### Where `search_func` looks

- **Shared libraries:** Jenkins checks out every library a build loads into
  `<workspace>@libs/`. Add your company's `@Library('...') _` line to the Jenkinsfile,
  or mark the libraries "load implicitly" in Jenkins, so they're there to search.
- **Agents, labels and tools:** read from the controller's `$JENKINS_HOME`
  (`nodes/*/config.xml`, `config.xml`, and the tool XML files such as
  `jenkins.plugins.nodejs.tools.NodeJSInstallation.xml`). That only works when the build
  runs on the controller (built-in node). On an agent, the tool says it can't see them
  and suggests trying the name directly. It never reads `credentials.xml` or `secrets/`.
- When the build runs on the controller, `run_shell` and `write_file` are turned off,
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
   `CREDENTIALS_ID`, `MAX_STEPS`, `MAX_TOKENS`. If your region serves Opus through a
   cross-region inference profile, set `AI_MODEL` to that profile's ID
   (`aws bedrock list-inference-profiles`).
5. Create a Pipeline job from this `Jenkinsfile`. The first run only registers the
   parameter. After that, use "Build with Parameters" and type the task.
