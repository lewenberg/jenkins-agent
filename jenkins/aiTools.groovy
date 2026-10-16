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
@Field List writtenFiles = []   // files write_file touched this build, for show_changes

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
            description: 'Find what already exists before building anything yourself. Searches three places: ' +
                '(1) workspace code: where a function is defined (def, function, func, fn, fun, sub, arrow functions, Java/C-style methods, shell functions); ' +
                "(2) the company's Jenkins shared libraries loaded by this build: global steps in vars/ (with their help text and call() signatures) and functions in src/; " +
                '(3) this Jenkins instance: agents with their labels, and tool installations such as Node.js versions (e.g. node22), JDKs, Maven and Gradle, with the name and type to use in the tool step. ' +
                'Leave name empty to list everything in a scope: all shared-library steps, or every agent, label and tool.',
            input_schema: [
                type: 'object',
                properties: [
                    name   : [type: 'string', description: 'Function, step, agent, label or tool name, e.g. deployApp, buildImage, node22. Empty lists everything in the scope.'],
                    scope  : [type: 'string', enum: ['all', 'workspace', 'libraries', 'jenkins'],
                              description: 'Where to look: workspace code, shared libraries, Jenkins agents/labels/tools, or all (default).'],
                    path   : [type: 'string', description: "For workspace: directory or file relative to the workspace. Default '.'."],
                    include: [type: 'string', description: "For workspace and libraries: only search files matching this glob, e.g. '*.groovy'."],
                    context: [type: 'integer', description: 'Lines to show after each function definition (0-60). Default 15.'],
                ],
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
        [
            name: 'show_changes',
            description: 'Show what has changed in the workspace: git status, the diff of changed files, and the content of new files. Also lists the files the agents wrote this build. Use it to review changes before they run.',
            input_schema: [
                type: 'object',
                properties: [
                    path: [type: 'string', description: "Only show changes under this file or directory. Default '.'."],
                ],
            ],
        ],
        [
            name: 'revert_file',
            description: 'Undo the changes to one file: restore it from the last commit, or delete it if it is new. Use it to clean up a wrong or unneeded change.',
            input_schema: [
                type: 'object',
                properties: [
                    path: [type: 'string', description: 'File path relative to the workspace.'],
                ],
                required: ['path'],
            ],
        ],
    ]
}

// ---------------------------------------------------------------------------
// Dispatch. Returns the text the model sees; throw (or error()) to report a failure.

String run(String name, Map input) {
    input = input ?: [:]
    // The controller's disk holds every Jenkins secret, so no shell or writes there.
    if (name in ['run_shell', 'write_file', 'revert_file'] && env.NODE_NAME in ['built-in', 'master']) {
        error("${name} is turned off when the build runs on the Jenkins controller. Use the read-only tools.")
    }
    switch (name) {
        case 'get_build_info': return getBuildInfo()
        case 'list_files':     return listFiles(input)
        case 'read_file':      return readWorkspaceFile(input)
        case 'search_func':    return searchFunc(input)
        case 'search_files':   return searchFiles(input)
        case 'run_shell':      return runShell(input)
        case 'write_file':     return writeWorkspaceFile(input)
        case 'show_changes':   return showChanges(input)
        case 'revert_file':    return revertFile(input)
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
    def name = ((input.name ?: '') as String).trim()
    def scope = (input.scope ?: 'all') as String
    if (name && !(name ==~ '[A-Za-z0-9_$.@+\\-]+')) error("'${name}' is not a name to search for.")
    // Function search builds a regex from the name, so it needs an identifier (or Class.method).
    def isIdent = name ==~ '[A-Za-z_$][A-Za-z0-9_$.]*'
    def context = Math.max(0, Math.min(60, (input.context == null ? 15 : input.context) as int))
    def include = input.include ? "--include=${q(input.include as String)}" : ''
    def sections = []

    if (scope in ['all', 'workspace'] && isIdent) {
        def out = findDefinitions(q(safePath(input.path ?: '.')), name, include, context)
        sections << "== Workspace code ==\n" + (out ?: "No definition of '${name}' in the workspace.")
    }
    if (scope in ['all', 'libraries']) {
        def out
        withEnv(["AI_NAME=${isIdent ? name : ''}", "AI_CONTEXT=${context}"]) {
            out = sh(returnStdout: true, label: 'AI: search shared libraries', script: LIBRARY_STEPS_SH).trim()
        }
        if (name && isIdent) {
            def defs = findDefinitions('"$WORKSPACE@libs"', name, include, context)
            if (defs) out += "\nFunctions named ${name}:\n" + defs
        }
        sections << "== Shared libraries (${env.WORKSPACE}@libs) ==\n" + (out ?: "Nothing named '${name}' in the shared libraries.")
    }
    if (scope in ['all', 'jenkins']) {
        def out
        withEnv(["AI_FILTER=${name}"]) {
            out = sh(returnStdout: true, label: 'AI: list Jenkins agents and tools', script: JENKINS_INVENTORY_SH).trim()
        }
        sections << "== Jenkins agents, labels and tools ==\n" + out
    }
    if (!sections) error("'${name}' isn't a function name; search it with scope 'jenkins' or 'all'.")
    return clip(sections.join('\n\n'))
}

// grep for definitions of a function; `where` is already shell-quoted.
String findDefinitions(String where, String name, String include, int context) {
    def n = name.tokenize('.').last().replace('$', '\\$')
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
    return sh(returnStdout: true, script: """#!/bin/sh
grep -rnIE -A ${context} --exclude-dir=.git --exclude-dir=node_modules --exclude-dir=.ai ${include} ${args} -- ${where} 2>/dev/null \\
  | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(return|if|while|for|switch|else|new|throw|await)[[:space:](]' | head -n 600
""").trim()
}

// Shared libraries are checked out next to the workspace, in <workspace>@libs/<library>/.
// A global step is vars/<step>.groovy (optional help in vars/<step>.txt) and runs its call() methods.
@Field final String LIBRARY_STEPS_SH = '''#!/bin/sh
libs="$WORKSPACE@libs"
if [ ! -d "$libs" ]; then
  echo "This build has no shared libraries checked out (no $libs). Load them with @Library in the Jenkinsfile, or mark them implicitly loaded in Jenkins."
  exit 0
fi
if [ -z "$AI_NAME" ]; then
  echo "Global steps (call them like built-in steps, e.g. stepName(...)):"
  for f in "$libs"/*/vars/*.groovy; do
    [ -e "$f" ] || continue
    help="${f%.groovy}.txt"
    about=""
    [ -e "$help" ] && about=$(sed -e 's/<[^>]*>//g' "$help" | tr -s ' \\n' ' ' | cut -c1-140)
    echo "  $(basename "$f" .groovy)  ($(echo "$f" | sed "s|^$libs/||"))  $about"
  done
  echo "Classes in src/:"
  find "$libs"/*/src -name '*.groovy' 2>/dev/null | sed "s|^$libs/[^/]*/src/||; s|\\.groovy$||; s|/|.|g; s|^|  |" | sort | head -n 200
  exit 0
fi
for f in "$libs"/*/vars/"$AI_NAME".groovy; do
  [ -e "$f" ] || continue
  echo "Global step $AI_NAME: $f"
  help="${f%.groovy}.txt"
  if [ -e "$help" ]; then echo "Help:"; sed -e 's/<[^>]*>//g' "$help" | head -c 3000; echo; fi
  echo "call() signatures:"
  grep -nE -B 8 -A "$AI_CONTEXT" '(^|[^A-Za-z0-9_])(def|void|Object|Map|String|List|boolean)[[:space:]]+call[[:space:]]*\\(' "$f"
done
'''

// Agents, labels and tool installations, read from the controller's $JENKINS_HOME.
// Only readable when the build runs on the controller; on an agent it says so.
// Reads only these config files, never credentials.xml or secrets/.
@Field final String JENKINS_INVENTORY_SH = '''#!/bin/sh
H="$JENKINS_HOME"
if [ -z "$H" ] || [ ! -r "$H/config.xml" ]; then
  echo "Can't read the Jenkins configuration from this machine (running on agent '$NODE_NAME')."
  echo "The agent and tool lists live on the controller, in \\$JENKINS_HOME; run the build on the built-in node to read them."
  echo "To check one name here instead, try it: tool name: 'node22', type: 'nodejs', or nodesByLabel('node22')."
  exit 0
fi
names_in() { tr -d '\\n\\r' < "$2" | grep -oE "<$1>.*</$1>" | grep -oE '<name>[^<]+</name>' | sed -E 's:</?name>::g' | tr '\\n' ' '; }
{
  echo "Agents (name: labels):"
  for c in "$H"/nodes/*/config.xml; do
    [ -r "$c" ] || continue
    printf '  %s: %s\\n' "$(basename "$(dirname "$c")")" "$(sed -n 's:.*<label>\\(.*\\)</label>.*:\\1:p' "$c" | head -n 1)"
  done
  echo "Controller and cloud template labels:"
  printf '  %s\\n' "$(grep -ohE '<(label|labelString|labels)>[^<]+</(label|labelString|labels)>' "$H"/config.xml "$H"/*[Cc]loud*.xml 2>/dev/null \\
    | sed -E 's/<[^>]+>//g' | tr ' ' '\\n' | grep -v '^$' | sort -u | tr '\\n' ' ')"
  echo "Tool installations (use as tool name: '<name>', type: '<type>'):"
  for f in "$H"/*.xml; do
    grep -q '<installations' "$f" 2>/dev/null || continue
    t=$(basename "$f" .xml)
    case "$t" in
      *NodeJSInstallation) ty=nodejs ;;
      hudson.tasks.Maven) ty=maven ;;
      hudson.plugins.gradle.Gradle) ty=gradle ;;
      *GoInstallation|*golang*) ty=go ;;
      hudson.plugins.git.GitTool) ty=git ;;
      *) ty=$t ;;
    esac
    printf '  type %s: %s\\n' "$ty" "$(names_in installations "$f")"
  done
  jdks=$(names_in jdks "$H/config.xml")
  [ -n "$jdks" ] && printf '  type jdk: %s\\n' "$jdks"
} | awk -v f="$AI_FILTER" 'BEGIN { f = tolower(f) } /^  / { if (f == "" || index(tolower($0), f)) print; next } { print }'
'''

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

// Every command and its output is also appended to .ai/output.txt (see outputLog()).
String runShell(Map input) {
    if (!input.command) error('command is required')
    def secs = Math.max(1, Math.min(1800, (input.timeout_seconds ?: 300) as int))
    writeFile file: '.ai/cmd.sh', text: input.command as String
    // The shell's own timeout, not the timeout step: that one would abort the whole build.
    def rc = sh(returnStatus: true, label: 'AI: run_shell', script: """#!/bin/bash
if command -v timeout >/dev/null 2>&1; then timeout -k 10 ${secs} bash .ai/cmd.sh; else bash .ai/cmd.sh; fi > .ai/cmd.out 2>&1
""")
    withEnv(["AI_RC=${rc}"]) {
        sh '#!/bin/sh\n{ printf \'\\n$ \'; cat .ai/cmd.sh; printf \'\\n[exit %s]\\n\' "$AI_RC"; cat .ai/cmd.out; } >> .ai/output.txt'
    }
    def note = rc == 124 ? " (timed out after ${secs}s)" : ''
    return clip("exit ${rc}${note}\n${readFile('.ai/cmd.out')}")
}

// The last maxChars characters of everything run_shell ran since the last reset.
String outputLog(int maxChars) {
    if (!fileExists('.ai/output.txt')) return '(nothing was run)'
    return sh(returnStdout: true, script: "#!/bin/sh\ntail -c ${maxChars} .ai/output.txt")
}

void resetOutputLog() {
    sh '#!/bin/sh\nrm -f .ai/output.txt'
}

String writeWorkspaceFile(Map input) {
    def file = safePath(input.path)
    def content = (input.content ?: '') as String
    writeFile file: file, text: content
    if (!(file in writtenFiles)) writtenFiles << file
    return "Wrote ${content.length()} characters to ${file}."
}

// The build's own files (.ai/, ai-result.md, ai-output.txt) are left out.
@Field final String NOT_OURS = "':(exclude).ai' ':(exclude)ai-result.md' ':(exclude)ai-output.txt'"

String showChanges(Map input) {
    def where = q(safePath(input.path ?: '.'))
    def out = sh(returnStdout: true, script: """#!/bin/sh
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo '(the workspace is not a git repository, so there is no diff)'
  exit 0
fi
echo '--- git status ---'
git status --short -- ${where} ${NOT_OURS}
echo '--- diff of changed files ---'
git diff HEAD -- ${where} ${NOT_OURS} 2>/dev/null || git diff -- ${where} ${NOT_OURS}
git ls-files --others --exclude-standard -- ${where} ${NOT_OURS} | head -n 50 | while IFS= read -r f; do
  echo "--- new file \$f ---"
  head -c 4000 "\$f"
  echo
done
""")
    def written = writtenFiles ? "Files the agents wrote this build: ${writtenFiles.join(', ')}" : 'The agents have not written any files this build.'
    return clip(out.trim() + '\n\n' + written)
}

String revertFile(Map input) {
    def file = safePath(input.path)
    if (file in ['', '.', './'] || file.startsWith('.git') || file.startsWith('.ai')) error("Can't revert '${file}'. Give one file.")
    def out = sh(returnStdout: true, script: """#!/bin/sh
f=${q(file)}
if git ls-files --error-unmatch -- "\$f" >/dev/null 2>&1; then
  git checkout HEAD -- "\$f" 2>&1 && echo "Restored \$f from the last commit."
elif [ -f "\$f" ]; then
  rm -f -- "\$f" && echo "Deleted new file \$f."
else
  echo "\$f is not a file; nothing to revert."
fi
""").trim()
    writtenFiles.remove(file)
    return out
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
