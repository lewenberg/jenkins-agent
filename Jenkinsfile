// Give Claude (Opus on Amazon Bedrock) a task in one multiline parameter; it carries
// the task out in this build's workspace using the tools in jenkins/aiTools.groovy.
//
// No server, no UI: the build calls Bedrock's Converse API with the AWS CLI, runs each
// tool call in Groovy, sends the result back, and repeats until the model is done.
// Everything it does shows in the console log; the final answer is archived as ai-result.md.
//
// Plugins: Pipeline, Pipeline Utility Steps (readJSON/writeJSON), CloudBees AWS Credentials.
// Agent:   sh and AWS CLI v2.

// --- Settings -----------------------------------------------------------------
// Bedrock model or inference profile ID. If your region needs a cross-region profile,
// use the one `aws bedrock list-inference-profiles` shows (e.g. a us. or global. prefix).
def AI_MODEL       = 'anthropic.claude-opus-5-5'
def AI_REGION      = 'us-east-1'
def CREDENTIALS_ID = 'bedrock-aws'   // Jenkins "AWS Credentials" allowed to call Bedrock
def MAX_STEPS      = 50              // model calls per build
def MAX_TOKENS     = 32000           // output tokens per model call

pipeline {
    agent any

    parameters {
        text(name: 'TASK', defaultValue: '',
             description: 'What the AI should do. It works in this job\'s workspace and can read, search, edit and run things.')
    }

    options {
        timeout(time: 2, unit: 'HOURS')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {
        stage('AI task') {
            steps {
                script {
                    if (!params.TASK?.trim()) error('TASK is empty.')
                    def tools = load 'jenkins/aiTools.groovy'
                    sh '#!/bin/sh\nrm -rf .ai && mkdir -p .ai'
                    withEnv(["AI_MODEL=${AI_MODEL}", "AI_REGION=${AI_REGION}"]) {
                        withCredentials([aws(credentialsId: CREDENTIALS_ID,
                                             accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                                             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY')]) {
                            def answer = runAgent(tools, params.TASK, MAX_STEPS, MAX_TOKENS)
                            writeFile file: 'ai-result.md', text: answer
                        }
                    }
                }
            }
        }
    }

    post {
        always {
            archiveArtifacts artifacts: 'ai-result.md', allowEmptyArchive: true
            sh '#!/bin/sh\nrm -rf .ai'
        }
    }
}

// The agent loop: ask the model, run the tools it asks for, send results back, repeat.
String runAgent(def tools, String task, int maxSteps, int maxTokens) {
    def system = [[text: """You are an engineer working inside a Jenkins build (job ${env.JOB_NAME}, build #${env.BUILD_NUMBER}).
Your working directory is the build workspace: ${env.WORKSPACE}.
Carry out the task you are given using your tools. Every tool call runs for real on the Jenkins agent.
Look before you change things: use search_func, search_files and read_file to find the code involved.
Work step by step and check your results (for example, run the build or tests after a change).
When you are done, reply with a short summary of what you did and anything that still needs attention. Use Markdown."""]]
    def toolConfig = [tools: []]
    for (def t in tools.definitions()) {
        toolConfig.tools << [toolSpec: [name: t.name, description: t.description, inputSchema: [json: t.input_schema]]]
    }
    writeJSON file: '.ai/system.json', json: system
    writeJSON file: '.ai/tools.json', json: toolConfig
    writeJSON file: '.ai/inference.json', json: [maxTokens: maxTokens]

    def messages = [[role: 'user', content: [[text: task]]]]
    for (int step = 1; step <= maxSteps; step++) {
        writeJSON file: '.ai/messages.json', json: messages
        def out = sh(label: "Bedrock call ${step}", returnStdout: true, script: '''#!/bin/sh
set -e
aws bedrock-runtime converse --region "$AI_REGION" --model-id "$AI_MODEL" \
  --system file://.ai/system.json --messages file://.ai/messages.json \
  --tool-config file://.ai/tools.json --inference-config file://.ai/inference.json \
  --cli-read-timeout 900 --output json
''')
        def resp = readJSON(text: out, returnPojo: true)
        def msg = resp.output.message
        messages << msg   // keep the whole reply (reasoning included) so the history stays valid

        def uses = []
        for (def block in msg.content) {
            if (block.text) echo "AI:\n${block.text}"
            if (block.toolUse) uses << block.toolUse
        }

        if (resp.stopReason != 'tool_use' || !uses) {
            if (resp.stopReason == 'max_tokens') echo 'Warning: the reply hit the output limit and was cut off.'
            def answer = msg.content.findAll { it.text }.collect { it.text }.join('\n\n')
            return answer ?: "(The model stopped with reason '${resp.stopReason}' and no text.)"
        }

        def results = []
        for (def tu in uses) {
            echo "Tool ${tu.name}: ${writeJSON(returnText: true, json: tu.input ?: [:])}"
            def status = 'success'
            def output
            try {
                output = tools.run(tu.name as String, (tu.input ?: [:]) as Map)
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
                throw e   // build aborted or timed out
            } catch (e) {
                status = 'error'
                output = "${e.class.simpleName}: ${e.message}"
            }
            echo "Result (${status}):\n${output.length() > 2000 ? output.substring(0, 2000) + '\n…' : output}"
            results << [toolResult: [toolUseId: tu.toolUseId, status: status, content: [[text: output ?: '(no output)']]]]
        }
        messages << [role: 'user', content: results]
    }
    error("Stopped after ${maxSteps} model calls without finishing.")
}
