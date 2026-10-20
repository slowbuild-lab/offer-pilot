#!/bin/zsh
set -e
cd "${0:A:h}"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x /opt/homebrew/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  elif [[ -x /usr/local/opt/openjdk@21/bin/java ]]; then
    export JAVA_HOME="/usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
  else
    export JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)"
  fi
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  print -u2 "未找到 Java 21。请安装 JDK 21，或设置 JAVA_HOME 后重试。"
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
# 兼容本机已安装的托管 Node；其他机器优先使用 PATH 中的 Node。
if ! command -v node >/dev/null 2>&1; then
  for node_candidate in "$HOME"/.meituan-catpaw/runtimes/node/versions/*/bin/node(NOn); do
    export PATH="${node_candidate:h}:$PATH"
    break
  done
fi
if ! command -v node >/dev/null 2>&1; then
  print -u2 "未找到 Node.js。请安装 Node.js 24，并确保 node 在 PATH 中。"
  exit 1
fi
export GRADLE_USER_HOME="$PWD/.gradle/user-home"
export npm_config_cache="$PWD/.gradle/npm-cache"
exec /bin/zsh ./gradlew --console=plain bootRun --args='--server.address=127.0.0.1'
