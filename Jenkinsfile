// Four Claude agents (Opus on Amazon Bedrock) take one task through a cycle inside this
// Jenkins build:
//
//   1. Builder           reads the code and makes the changes the task asks for.
//   2. Validator         fresh context, its own prompt: checks the changes against the task
//                        (versions, configs, the reason for each change) before anything
//                        runs, and fixes or reverts what the builder got wrong.
//   3. Runner            runs it in the shell (build, test, push) and reports errors.
//   4. Output validator  checks the run's output against the task. It can question the
//                        other agents. Pass ends the build.
//
// Any failed check sends its findings back to the builder for the next cycle, up to
// MAX_CYCLES. No server, no UI: each model call is the AWS CLI calling Bedrock's Converse
// API, and every tool call runs in Groovy (jenkins/aiTools.groovy). Everything shows in the
// console log; the report is archived as ai-result.md and the run output as ai-output.txt.
//
// Plugins: Pipeline, Pipeline Utility Steps (readJSON/writeJSON), CloudBees AWS Credentials.
// Agent:   sh and AWS CLI v2.

// --- Settings -----------------------------------------------------------------
// Bedrock model or inference profile ID. If your region needs a cross-region profile,
// use the one `aws bedrock list-inference-profiles` shows (e.g. a us. or global. prefix).
def AI_MODEL       = 'anthropic.claude-opus-5-5'
def AI_REGION      = 'us-east-1'
def CREDENTIALS_ID = 'bedrock-aws'   // Jenkins "AWS Credentials" allowed to call Bedrock
def PROMPT_VERSION = '2'             // bump when you change a prompt; it's in every report
def MAX_CYCLES     = 3               // builder → validator → runner → output validator rounds
def MAX_STEPS      = 40              // model calls per agent turn
def MAX_TOKENS     = 32000           // output tokens per model call
def OUTPUT_CHARS   = 30000           // characters of run output sent to the output validator

pipeline {
    agent any

    parameters {
        // To add one: add it here and to `context` below; every agent sees it.
        string(name: 'TEAM', defaultValue: '', description: 'Team that owns the app.')
        string(name: 'ORG',  defaultValue: '', description: 'Org (e.g. the GitHub org or business unit).')
        string(name: 'APP',  defaultValue: '', description: 'App name.')
        text(name: 'TASK', defaultValue: '',
             description: 'What the AI should do, e.g. "Build my grails app and push it. Use the buildGrails and pushImage steps."')
    }

    options {
        timeout(time: 3, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {
        stage('AI task') {
            steps {
                script {
                    if (!params.TASK?.trim()) error('TASK is empty.')
                    def tools = load 'jenkins/aiTools.groovy'
                    sh '#!/bin/sh\nrm -rf .ai && mkdir -p .ai'
                    def cfg = [
                        task: params.TASK.trim(),
                        context: [Team: params.TEAM, Org: params.ORG, App: params.APP],
                        model: AI_MODEL, promptVersion: PROMPT_VERSION, maxCycles: MAX_CYCLES,
                        maxSteps: MAX_STEPS, maxTokens: MAX_TOKENS, outputChars: OUTPUT_CHARS, calls: 0,
                    ]
                    def result
                    withEnv(["AI_MODEL=${AI_MODEL}", "AI_REGION=${AI_REGION}"]) {
                        withCredentials([aws(credentialsId: CREDENTIALS_ID,
                                             accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            result = runCycles(tools, cfg)
                        }
                    }
                    writeFile file: 'ai-result.md', text: result.report
                    if (!result.passed) error(result.outcome)
                    echo result.outcome
                }
            }
        }
    }

    post {
        always {
            sh '#!/bin/sh\ncp .ai/output.txt ai-output.txt 2>/dev/null || true'
            archiveArtifacts artifacts: 'ai-result.md, ai-output.txt', allowEmptyArchive: true
            sh '#!/bin/sh\nrm -rf .ai'
        }
    }
}

// ---------------------------------------------------------------------------
// The agents

@groovy.transform.Field
final List READ_ONLY = ['get_build_info', 'list_files', 'read_file', 'search_func', 'search_files', 'show_changes']

// Each agent: its tools, the statuses it can finish with (the last one is the failure), and
// its prompt. The prompts are what to change to tune an agent; bump PROMPT_VERSION when you do.
Map agents() {
    return [
        builder: [
            title: 'Builder',
            tools: READ_ONLY + ['write_file', 'revert_file'],
            statuses: ['done', 'blocked'],
            statusHelp: 'done: the changes are made. blocked: the request cannot be done; say what is missing.',
            prompt: '''Your job: carry out the request by changing the code and configuration in the workspace.
- Look before you change anything: search_func, search_files and read_file. Use what the company already has (shared-library steps, agent labels, tool installations such as JDKs, Gradle and Node versions) instead of writing your own.
- If the request names tools or steps to use, use those.
- You can't run commands. After you finish, the validator checks your changes and then the runner runs them, so your report must tell the runner exactly what to run.
- If earlier cycles failed, their findings are in the message. Fix the causes, not the symptoms. The workspace still has the changes from earlier cycles.
- finish with: summary = what you did; details = every file you changed and why, then the exact commands the runner should run (build, test, push), in order.''',
        ],
        validator: [
            title: 'Validator',
            tools: READ_ONLY + ['write_file', 'revert_file'],
            statuses: ['pass', 'fixed', 'fail'],
            statusHelp: 'pass: correct as is. fixed: you corrected it and it is now right (say what you changed). fail: the builder must rework it (list each problem and what to do).',
            prompt: '''Your job: check the builder's changes before anything runs. You start fresh: don't trust the builder's report; look at the changes themselves (show_changes, read_file).
Check:
- The changes do everything the request asks, and nothing it doesn't.
- Versions: language, framework and dependency versions fit each other and what this Jenkins has (search_func with scope jenkins for JDKs, Gradle, Node; the project's wrapper and build files).
- Configuration: build files, application config, Jenkinsfiles and environment settings are valid and match the team, org and app.
- Reasons: every change traces back to the request. No leftovers, debug code, unrelated edits or secrets written into files.
- The commands the builder wants run make sense and are safe.
- Obvious bugs: syntax errors, wrong imports, missing files, wrong paths.
Clean up what the builder got wrong: fix small mistakes yourself with write_file, and undo wrong or unneeded changes with revert_file. Send it back (fail) only when it needs real rework.''',
        ],
        runner: [
            title: 'Runner',
            tools: READ_ONLY + ['run_shell'],
            statuses: ['pass', 'fail'],
            statusHelp: 'pass: everything the request needs ran successfully. fail: something failed; give each failing command, its exit code, the key error lines and the likely cause.',
            prompt: '''Your job: run what the builder prepared, in the shell, and report exactly what happened. Don't edit files; if something needs a code change, report it.
- Run the builder's commands in order, plus anything else the request needs (build, test, push). Check the toolchain first when it matters (java -version, ./gradlew --version).
- Retry a failed command at most once, and only when the cause is clearly the environment (a flaky download), not the code.
- Don't do anything destructive the request didn't ask for: no force pushes, no deleting branches, tags or remote resources.
- Everything you run and its output is saved for the output validator.''',
        ],
        output_validator: [
            title: 'Output validator',
            tools: READ_ONLY + ['ask_agent'],
            statuses: ['pass', 'fail'],
            statusHelp: 'pass: the request is met. fail: list each problem and what the builder should do about it.',
            prompt: '''Your job: decide from the run's output whether the request was actually met. The end of the output is in the message; all of it is in .ai/output.txt (read_file).
- Judge from the output itself, not the runner's opinion: exit codes, test results, warnings that matter, that the build artifact exists, that the push went through (remote, branch, commit or image tag).
- Check every part of the request.
- If something is unclear, ask_agent the builder, validator or runner. Ask the validator to re-check code you suspect.''',
        ],
    ]
}

String systemPrompt(Map cfg, String name, boolean answering) {
    def a = agents()[name]
    def context = cfg.context.findAll { it.value?.trim() }.collect { "${it.key}: ${it.value.trim()}" }.join('\n') ?: '(none given)'
    def text = """You are the ${a.title}, one of four AI agents working on a request inside a Jenkins build (job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}).
The working directory is the build workspace: ${env.WORKSPACE}. Every tool call runs for real on the Jenkins agent.
The team: the builder makes the changes; the validator checks them before anything runs; the runner runs them in the shell; the output validator checks the run's output against the request. A failed check sends its findings back to the builder for another cycle.

About the request:
${context}

${a.prompt}

search_func finds what the company already has: functions in the workspace, the company's Jenkins shared-library steps, and this Jenkins's agents, labels and tool installations.
When you are done, call finish. Its status: ${a.statusHelp}
Keep reports factual and specific. Use Markdown."""
    if (answering) {
        text += """

Right now you are not doing your usual job: the output validator is asking you a question. Look at the workspace as needed with your read-only tools, then call finish with status answered and your answer in summary. Don't change anything."""
    }
    return text
}

// ---------------------------------------------------------------------------
// The cycle

Map runCycles(def tools, Map cfg) {
    def cycles = []
    def outcome = "Failed: no cycle passed in ${cfg.maxCycles}."
    def passed = false
    for (int n = 1; n <= cfg.maxCycles; n++) {
        def c = [n: n]
        cycles << c
        stage("Cycle ${n}: builder") {
            c.builder = runAgent(tools, cfg, 'builder', builderMessage(cfg, cycles), false)
        }
        if (c.builder.status == 'blocked') {
            c.failedAt = 'builder'
            outcome = "Blocked: the builder couldn't do the request (cycle ${n})."
            break
        }
        stage("Cycle ${n}: validator") {
            c.validator = runAgent(tools, cfg, 'validator', """Request:
${cfg.task}

The builder's report (check it, don't trust it):
${show(c.builder)}""", false)
        }
        if (c.validator.status == 'fail') { c.failedAt = 'validator'; continue }

        tools.resetOutputLog()
        stage("Cycle ${n}: runner") {
            c.runner = runAgent(tools, cfg, 'runner', """Request:
${cfg.task}

The builder's report:
${show(c.builder)}

The validator's check:
${show(c.validator)}""", false)
        }
        if (c.runner.status == 'fail') { c.failedAt = 'runner'; continue }

        stage("Cycle ${n}: output validator") {
            c.output = runAgent(tools, cfg, 'output_validator', """Request:
${cfg.task}

The runner's report:
${show(c.runner)}

The run's output (the last ${cfg.outputChars} characters):
${tools.outputLog(cfg.outputChars as int)}""", false)
        }
        if (c.output.status == 'pass') {
            passed = true
            outcome = "Passed in cycle ${n}."
            break
        }
        c.failedAt = 'output validator'
    }
    return [passed: passed, outcome: outcome, report: report(cfg, cycles, outcome)]
}

String builderMessage(Map cfg, List cycles) {
    def text = "Request:\n${cfg.task}"
    def failed = cycles.findAll { it.failedAt }
    if (failed) {
        text += "\n\nThis is cycle ${cycles.size()}. Earlier cycles did not pass:"
        for (def c in failed) {
            def key = [validator: 'validator', runner: 'runner', 'output validator': 'output'][c.failedAt]
            text += "\n\n## Cycle ${c.n}: failed at the ${c.failedAt}\nWhat you did then: ${c.builder.summary}\n\nThe ${c.failedAt}'s findings:\n${show(c[key])}"
        }
    }
    return text
}

String show(Map r) {
    return "Status: ${r.status}\n${r.summary}" + (r.details ? "\n\n${r.details}" : '')
}

String report(Map cfg, List cycles, String outcome) {
    def context = cfg.context.findAll { it.value?.trim() }.collect { "${it.key}: ${it.value.trim()}" }.join(' · ')
    def text = """# AI result: ${outcome}

Build: ${env.JOB_NAME} #${env.BUILD_NUMBER} · Model: ${cfg.model} · Prompt version: ${cfg.promptVersion}${context ? ' · ' + context : ''}

## Request

${cfg.task}
"""
    def parts = [['builder', 'Builder'], ['validator', 'Validator'], ['runner', 'Runner'], ['output', 'Output validator']]
    for (def c in cycles) {
        text += "\n## Cycle ${c.n}\n"
        for (def p in parts) {
            def r = c[p[0]]
            if (r) text += "\n### ${p[1]}: ${r.status}\n\n${r.summary}\n" + (r.details ? "\n${r.details}\n" : '')
        }
    }
    return text
}

// ---------------------------------------------------------------------------
// One agent turn: ask the model, run the tools it asks for, send the results back, until
// it calls finish. Each turn starts a fresh conversation with that agent's own prompt.
// answering: the agent answers another agent's question, with read-only tools.

Map runAgent(def tools, Map cfg, String name, String message, boolean answering) {
    def a = agents()[name]
    def allowed = answering ? a.tools.findAll { it in READ_ONLY } : a.tools
    def statuses = answering ? ['answered'] : a.statuses
    cfg.calls = cfg.calls + 1
    def dir = ".ai/${cfg.calls}-${name}"
    def label = answering ? "${a.title} (asked)" : a.title

    def specs = tools.definitions().findAll { it.name in allowed }
    if ('ask_agent' in allowed) specs << askAgentSpec()
    specs << finishSpec(statuses)
    sh "#!/bin/sh\nmkdir -p '${dir}'"
    writeJSON file: "${dir}/system.json", json: [[text: systemPrompt(cfg, name, answering)]]
    writeJSON file: "${dir}/tools.json", json: [tools: specs.collect { [toolSpec: [name: it.name, description: it.description, inputSchema: [json: it.input_schema]]] }]
    writeJSON file: "${dir}/inference.json", json: [maxTokens: cfg.maxTokens]

    def messages = [[role: 'user', content: [[text: message]]]]
    int nudges = 0
    for (int step = 1; step <= cfg.maxSteps; step++) {
        writeJSON file: "${dir}/messages.json", json: messages
        def out
        withEnv(["AI_DIR=${dir}"]) {
            out = sh(label: "${label}: model call ${step}", returnStdout: true, script: '''#!/bin/sh
set -e
aws bedrock-runtime converse --region "$AI_REGION" --model-id "$AI_MODEL" \
  --system "file://$AI_DIR/system.json" --messages "file://$AI_DIR/messages.json" \
  --tool-config "file://$AI_DIR/tools.json" --inference-config "file://$AI_DIR/inference.json" \
  --cli-read-timeout 900 --output json
''')
        }
        def resp = readJSON(text: out, returnPojo: true)
        def msg = resp.output.message
        messages << msg   // keep the whole reply (reasoning included) so the history stays valid

        def uses = []
        for (def block in msg.content) {
            if (block.text) echo "${label}:\n${block.text}"
            if (block.toolUse) uses << block.toolUse
        }

        if (!uses) {
            if (resp.stopReason == 'max_tokens') echo "Warning: the ${label}'s reply hit the output limit and was cut off."
            def text = msg.content.findAll { it.text }.collect { it.text }.join('\n\n')
            if (nudges++ < 2) {
                messages << [role: 'user', content: [[text: 'Call finish to report your result.']]]
                continue
            }
            return [status: statuses.last(), summary: text ?: "(stopped with reason '${resp.stopReason}' without calling finish)", details: '']
        }

        def results = []
        def finished = null
        for (def tu in uses) {
            def input = (tu.input ?: [:]) as Map
            def status = 'success'
            def output
            if (tu.name == 'finish') {
                if (input.status in statuses) {
                    finished = input
                    output = 'Recorded.'
                } else {
                    status = 'error'
                    output = "status must be one of: ${statuses.join(', ')}"
                }
            } else {
                echo "${label} → ${tu.name}: ${writeJSON(returnText: true, json: input)}"
                try {
                    if (!(tu.name in allowed)) error("The ${label} can't use ${tu.name}.")
                    output = tu.name == 'ask_agent' ? askAgent(tools, cfg, input) : tools.run(tu.name as String, input)
                } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                    throw e   // build aborted or timed out
                } catch (e) {
                    status = 'error'
                    output = "${e.class.simpleName}: ${e.message}"
                }
                echo "Result (${status}):\n${output.length() > 2000 ? output.substring(0, 2000) + '\n…' : output}"
            }
            results << [toolResult: [toolUseId: tu.toolUseId, status: status, content: [[text: output ?: '(no output)']]]]
        }
        if (finished) {
            echo "${label} finished: ${finished.status}\n${finished.summary ?: ''}"
            return [status: finished.status, summary: (finished.summary ?: '') as String, details: (finished.details ?: '') as String]
        }
        messages << [role: 'user', content: results]
    }
    return [status: statuses.last(), summary: "Stopped after ${cfg.maxSteps} model calls without finishing.", details: '']
}

// The output validator's questions to another agent; that agent answers in its own fresh turn.
String askAgent(def tools, Map cfg, Map input) {
    def who = input.agent as String
    if (!(who in ['builder', 'validator', 'runner'])) error('agent must be builder, validator or runner')
    if (!input.question) error('question is required')
    def r = runAgent(tools, cfg, who, "The request being worked on:\n${cfg.task}\n\nThe output validator asks:\n${input.question}", true)
    return r.summary + (r.details ? "\n\n${r.details}" : '')
}

Map finishSpec(List statuses) {
    return [
        name: 'finish',
        description: 'Report your result and end your turn. Call it once, when you are done.',
        input_schema: [
            type: 'object',
            properties: [
                status : [type: 'string', enum: statuses, description: 'Your verdict.'],
                summary: [type: 'string', description: 'The result in a few sentences.'],
                details: [type: 'string', description: 'Everything the next agent needs: files changed and why, commands to run, failing commands with their errors, problems with what to do about each.'],
            ],
            required: ['status', 'summary'],
        ],
    ]
}

Map askAgentSpec() {
    return [
        name: 'ask_agent',
        description: 'Ask the builder, validator or runner a question. It answers in a fresh turn with read-only tools, looking at the workspace as it is now. Ask the validator to re-check code against the request.',
        input_schema: [
            type: 'object',
            properties: [
                agent   : [type: 'string', enum: ['builder', 'validator', 'runner']],
                question: [type: 'string', description: 'The question, with whatever context the agent needs.'],
            ],
            required: ['agent', 'question'],
        ],
    ]
}
