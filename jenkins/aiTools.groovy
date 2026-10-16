// Tools the AI can call.
//
// The Jenkinsfile loads this file, sends definitions() to Bedrock with every request
// (that is how the model knows what it can do), and calls run() for each tool call.
// Everything here runs on the Jenkins agent, inside the build's workspace, with the
// build's permissions.
//
// To add a tool: add its spec to definitions() and a case to run(). Keep the
// description specific; the model only knows what it says.

import groovy.transform.Field

@Field final int MAX_OUTPUT = 40000

// ---------------------------------------------------------------------------
// Specs sent to the model: name, description, input_schema (JSON Schema)

List definitions() {
    return [
        [
            name: 'get_build_info',
            description: 'Get details about the running Jenkins build: job name, build number and URL, agent, workspace path, git branch and commit, and non-secret environment variables.',
            input_schema: [type: 'object', properties: [:]],
        ],
        [
            name: 'list_files',
            description: 'List files and directories in the workspace, recursively up to a depth. Paths are relative to the workspace root. .git is skipped.',
            input_schema: [
                type: 'object',
                properties: [
                    path : [type: 'string', description: "Directory relative to the workspace. Default '.'."],
                    depth: [type: 'integer', description: 'How many levels deep to list (1-6). Default 2.'],
                ],
            ],
        ],
        [
            name: 'read_file',
            description: 'Read a text file from the workspace, optionally starting at a line number.',
            input_schema: [
                type: 'object',
                properties: [
                    path      : [type: 'string', description: 'File path relative to the workspace.'],
                    start_line: [type: 'integer', description: 'First line to return (1-based). Default 1.'],
                    max_lines : [type: 'integer', description: 'How many lines to return. Default 400.'],
                ],
                required: ['path'],
            ],
        ],
        [
            name: 'search_func',
            description: 'Find where a function or method is defined, by name. Recognizes common definition forms (def, function, func, fn, fun, sub, arrow functions and assignments, Java/C-style methods). Returns each definition as path:line with the lines that follow it. Use this before search_files when you know the function name.',
            input_schema: [
                type: 'object',
                properties: [
                    name   : [type: 'string', description: 'Function or method name, e.g. buildImage or parse_config.'],
                    path   : [type: 'string', description: "Directory or file relative to the workspace. Default '.'."],
                    include: [type: 'string', description: "Only search files matching this glob, e.g. '*.groovy'."],
                    context: [type: 'integer', description: 'Lines to show after each definition (0-60). Default 15.'],
                ],
                required: ['name'],
            ],
        ],
        [
            name: 'search_files',
            description: 'Search file contents in the workspace with grep (extended regex). Returns matching lines as path:line:text.',
            input_schema: [
                type: 'object',
                properties: [
                    pattern    : [type: 'string', description: 'Extended regular expression to search for.'],
                    path       : [type: 'string', description: "Directory or file relative to the workspace. Default '.'."],
                    include    : [type: 'string', description: "Only search files matching this glob, e.g. '*.groovy'."],
                    ignore_case: [type: 'boolean', description: 'Case-insensitive search.'],
                ],
                required: ['pattern'],
            ],
        ],
        [
            name: 'run_shell',
            description: 'Run a shell command (bash) on the Jenkins agent, in the workspace. Returns the exit code and combined stdout/stderr. Use for git, builds, tests, and anything the other tools cannot do.',
            input_schema: [
                type: 'object',
                properties: [
                    command        : [type: 'string', description: 'The bash command or script to run.'],
                    timeout_seconds: [type: 'integer', description: 'Kill the command after this many seconds (max 1800). Default 300.'],
                ],
                required: ['command'],
            ],
        ],
        [
            name: 'write_file',
            description: 'Create or overwrite a text file in the workspace with the given content.',
            input_schema: [
                type: 'object',
                properties: [
                    path   : [type: 'string', description: 'File path relative to the workspace.'],
                    content: [type: 'string', description: 'The full new content of the file.'],
                ],
                required: ['path', 'content'],
            ],
        ],
    ]
}

// ---------------------------------------------------------------------------
// Dispatch. Returns the text the model sees; throw (or error()) to report a failure.

String run(String name, Map input) {
    input = input ?: [:]
    switch (name) {
        case 'get_build_info': return getBuildInfo()
        case 'list_files':     return listFiles(input)
        case 'read_file':      return readWorkspaceFile(input)
        case 'search_func':    return searchFunc(input)
        case 'search_files':   return searchFiles(input)
        case 'run_shell':      return runShell(input)
        case 'write_file':     return writeWorkspaceFile(input)
    }
    error("Unknown tool '${name}'.")
}

// ---------------------------------------------------------------------------
// Tools

String getBuildInfo() {
    def secretish = ~/(?i).*(TOKEN|SECRET|PASSWORD|PASSWD|KEY|CREDENTIAL|AUTH|COOKIE|SESSION).*/
    def envVars = sh(script: '#!/bin/sh\nenv', returnStdout: true).readLines()
        .findAll { it.contains('=') && !(it.split('=', 2)[0] ==~ secretish) }
        .sort()
    def git = sh(script: '#!/bin/sh\ngit rev-parse --abbrev-ref HEAD 2>/dev/null && git log -1 --format="%H %an: %s" 2>/dev/null || echo "(not a git repository)"',
                 returnStdout: true).trim()
    return """Job: ${env.JOB_NAME}
Build: #${env.BUILD_NUMBER} ${env.BUILD_URL ?: ''}
Agent: ${env.NODE_NAME}
Workspace: ${env.WORKSPACE}
Git: ${git}
Environment:
${clip(envVars.join('\n'))}"""
}

String listFiles(Map input) {
    def dir = safePath(input.path ?: '.')
    def depth = Math.max(1, Math.min(6, (input.depth ?: 2) as int))
    def out = sh(returnStdout: true, script: """#!/bin/sh
find ${q(dir)} -maxdepth ${depth} -not -path '*/.git' -not -path '*/.git/*' -not -path './.ai*' 2>&1 | sort | head -n 800
""")
    return clip(out) ?: '(empty)'
}

String readWorkspaceFile(Map input) {
    def file = safePath(input.path)
    if (!fileExists(file)) error("No such file: ${file}")
    def start = Math.max(1, (input.start_line ?: 1) as int)
    def count = Math.max(1, Math.min(4000, (input.max_lines ?: 400) as int))
    def out = sh(returnStdout: true, script: """#!/bin/sh
sed -n '${start},${start + count - 1}p' ${q(file)}
echo "--- from line ${start}; the file has \$(wc -l < ${q(file)}) lines ---"
""")
    return clip(out)
}

String searchFunc(Map input) {
    def name = (input.name ?: '') as String
    // The name goes into a regex, so only identifier characters (plus . for Class.method).
    if (!(name ==~ /[A-Za-z_$][A-Za-z0-9_$.]*/)) error("'${name}' is not a function name.")
    def n = name.tokenize('.').last().replace('$', '\\$')
    def where = safePath(input.path ?: '.')
    def include = input.include ? "--include=${q(input.include as String)}" : ''
    def context = Math.max(0, Math.min(60, (input.context == null ? 15 : input.context) as int))
    // Definition forms, one alternative each:
    //   def/function/func/fn/fun/sub/proc NAME      (Groovy, Python, JS, Go, Rust, Kotlin, Perl, Tcl)
    //   func (r *T) NAME                            (Go methods)
    //   NAME = function / NAME: function / NAME = (...) => / NAME = async (   (JS/TS)
    //   [modifiers] Type NAME(  at line start, not a call (Java, C#, C/C++, Groovy typed methods)
    //   NAME() {  at line start                     (shell functions)
    def patterns = [
        "(^|[^A-Za-z0-9_])(def|function|func|fn|fun|sub|proc)[[:space:]]+\\*?${n}([^A-Za-z0-9_]|\$)",
        "(^|[^A-Za-z0-9_])func[[:space:]]+\\([^)]*\\)[[:space:]]*${n}[[:space:]]*\\(",
        "(^|[^A-Za-z0-9_.])${n}[[:space:]]*[:=][[:space:]]*(async[[:space:]]+)?(function|\\(|[A-Za-z_][A-Za-z0-9_]*[[:space:]]*=>)",
        "^[[:space:]]*([A-Za-z_][A-Za-z0-9_<>,.?\\[\\]]*[[:space:]]+)+\\**${n}[[:space:]]*\\([^;]*\$",
        "^[[:space:]]*${n}[[:space:]]*\\(\\)[[:space:]]*\\{",
    ]
    def args = patterns.collect { "-e ${q(it)}" }.join(' ')
    def out = sh(returnStdout: true, script: """#!/bin/sh
grep -rnIE -A ${context} --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.ai ${include} ${args} -- ${q(where)} 2>/dev/null \\
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(return|if|while|for|switch|else|new|throw|await)[[:space:](]' | head -n 600
""")
    return clip(out) ?: "No definition of '${name}' found. Try search_files with the name to find where it is used or imported."
}

String searchFiles(Map input) {
    if (!input.pattern) error('pattern is required')
    def where = safePath(input.path ?: '.')
    def flags = '-rnIE' + (input.ignore_case ? 'i' : '')
    def include = input.include ? "--include=${q(input.include as String)}" : ''
    def out = sh(returnStdout: true, script: """#!/bin/sh
grep ${flags} --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.ai ${include} -e ${q(input.pattern as String)} -- ${q(where)} 2>/dev/null | head -n 300
""")
    return clip(out) ?: 'No matches.'
}

String runShell(Map input) {
    if (!input.command) error('command is required')
    def secs = Math.max(1, Math.min(1800, (input.timeout_seconds ?: 300) as int))
    writeFile file: '.ai/cmd.sh', text: input.command as String
    def rc
    timeout(time: secs, unit: 'SECONDS') {
        rc = sh(returnStatus: true, label: 'AI: run_shell', script: '#!/bin/bash\nbash .ai/cmd.sh > .ai/cmd.out 2>&1')
    }
    return clip("exit ${rc}\n${readFile('.ai/cmd.out')}")
}

String writeWorkspaceFile(Map input) {
    def file = safePath(input.path)
    def content = (input.content ?: '') as String
    writeFile file: file, text: content
    return "Wrote ${content.length()} characters to ${file}."
}

// ---------------------------------------------------------------------------
// Helpers

// Workspace-relative paths only: no absolute paths, no home, no '..'.
String safePath(def p) {
    def s = ((p ?: '.') as String).trim()
    if (s.startsWith('/') || s.startsWith('~') || s.tokenize('/').contains('..')) {
        error("Path '${s}' is outside the workspace. Use a path relative to the workspace.")
    }
    return s
}

// Single-quote a string for the shell.
String q(String s) {
    return "'" + s.replace("'", "'\"'\"'") + "'"
}

String clip(String s) {
    if (s == null) return ''
    return s.length() <= MAX_OUTPUT ? s : s.substring(0, MAX_OUTPUT) + "\n… (truncated, ${s.length() - MAX_OUTPUT} more characters)"
}

return this
