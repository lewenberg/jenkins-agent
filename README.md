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
| `search_func` | Find where a function is defined, by name: `def`, `function`, `func`, `fn`, `fun`, Go methods, JS arrow functions and assignments, Java/C-style methods, shell functions |
| `search_files` | grep across the workspace |
| `run_shell` | Run bash in the workspace (exit code + output) |
| `write_file` | Create or overwrite a file |

The model gets these specs with every request, and the system prompt tells it they
run on the Jenkins agent. To add a tool, add a spec to `definitions()` and a `case` to
`run()`.

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
