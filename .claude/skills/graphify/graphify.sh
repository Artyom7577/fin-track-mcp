#!/usr/bin/env bash
# graphify — type-landscape & module-dependency diagrams + mechanical architecture checks for the Maven modules.
#
#   bash .claude/skills/graphify/graphify.sh generate   (re)write docs/generated/{type-landscape,module-deps}.md
#   bash .claude/skills/graphify/graphify.sh check      print findings; exit 2 on a HARD violation (dependency
#                                                       direction), exit 0 with advisory warnings otherwise
#
# Portable on purpose: macOS stock bash 3.2 (no associative arrays, no mapfile, no ${var,,}), BSD grep/sed/awk, and
# the system perl for the one declaration-parsing pass. No grep -P, no sed -i, no stat. When the modules hold no Java
# sources yet, both commands exit 0 silently.
#
# Speed matters: the PostToolUse hook runs generate + check after every Java edit, so nothing forks per file or per
# import line — one perl pass scans every source, one grep per module collects imports, loops use bash expansions.
set -u

ROOT="${CLAUDE_PROJECT_DIR:-}"
if [ -z "$ROOT" ]; then
  ROOT=$(cd "$(dirname "$0")/../../.." 2>/dev/null && pwd)
fi
cd "$ROOT" 2>/dev/null || exit 0

BACKEND="."
# Module keys (= the package segment after com.artyomh.finance, adapters without the "adapter." prefix).
MODULES="domain application parser persistence fx mockdata mcp"
OUT_DIR="docs/generated"
DIRECTION="mcp → adapters/application/domain, adapters → application → domain, mockdata → domain; adapters never → each other"

# Module key → Maven module directory.
module_dir() {
  case "$1" in
    domain|application|mockdata) echo "$1" ;;
    parser|persistence|fx) echo "adapter-$1" ;;
    mcp) echo "mcp-server" ;;
  esac
}

# Allowed one-way edges (CLAUDE.md, hexagonal). Nothing depends on mcp; domain depends on nothing.
dep_allowed() { # from to
  case "$1:$2" in
    application:domain) return 0 ;;
    parser:application|parser:domain|persistence:application|persistence:domain|fx:application|fx:domain) return 0 ;;
    mockdata:domain) return 0 ;;
    mcp:domain|mcp:application|mcp:parser|mcp:persistence|mcp:fx|mcp:mockdata) return 0 ;;
    *) return 1 ;;
  esac
}

# Test sources may also use the mock-data fixtures.
test_dep_allowed() { # from to
  if [ "$2" = mockdata ] && [ "$1" != mockdata ]; then
    return 0
  fi
  dep_allowed "$1" "$2"
}

# import_target <import line> — the module key an import of com.artyomh.finance.* points at.
import_target() {
  local t=${1##*com.artyomh.finance.}
  case "$t" in
    adapter.*) t=${t#adapter.}; echo "${t%%.*}" ;;
    *) echo "${t%%.*}" ;;
  esac
}

is_module() {
  case " $MODULES " in
    *" $1 "*) return 0 ;;
    *) return 1 ;;
  esac
}

has_sources() {
  local m d
  for m in $MODULES; do
    d=$(module_dir "$m")
    if [ -d "$BACKEND/$d/src" ] && [ -n "$(find "$BACKEND/$d/src" -name '*.java' -print 2>/dev/null | head -n 1)" ]; then
      return 0
    fi
  done
  return 1
}

# One row per top-level public type: module|scope|kind|name|sealed|parents|permits|refs|exempt|file
#   scope   main|test        parents/permits/refs  comma lists (generics and package qualifiers stripped)
#   refs    record components naming another main-source project type        exempt  yes for @FunctionalInterface /
#   @HttpExchange interfaces (implemented by a lambda or a Spring proxy, never by a class)
scan_types() {
  local m scope d
  for m in $MODULES; do
    d=$(module_dir "$m")
    for scope in main test; do
      if [ -d "$BACKEND/$d/src/$scope/java" ]; then
        find "$BACKEND/$d/src/$scope/java" -name '*.java' ! -name 'package-info.java' 2>/dev/null \
          | sed "s|^|$m	$scope	|"
      fi
    done
  done | perl -e '
    my (@rows, %known);
    sub names {
      my ($list) = @_;
      my @out;
      for my $n (split /,/, $list) {
        $n =~ s/^\s+|\s+$//g;
        $n =~ s/.*\.//;
        push @out, $n if $n =~ /^[A-Za-z_][A-Za-z0-9_]*$/;
      }
      return join(",", @out);
    }
    while (my $line = <STDIN>) {
      chomp $line;
      my ($m, $scope, $f) = split /\t/, $line, 3;
      open(my $fh, "<", $f) or next;
      my $text = do { local $/; <$fh> };
      close $fh;
      my $exempt = ($text =~ /\@(?:[\w.]+\.)?(?:FunctionalInterface|HttpExchange)\b/) ? "yes" : "no";
      $text =~ s{/\*.*?\*/}{ }gs;
      $text =~ s{(?<![:"])//[^\n]*}{ }g;
      $text =~ tr/\n\r\t/   /;
      $text =~ /\bpublic\s+(?:(?:final|abstract|sealed|non-sealed|static|strictfp)\s+)*(record|class|interface|enum)\s+([A-Za-z0-9_]+)([^{]*)/ or next;
      my ($kind, $name, $rest) = ($1, $2, $3);
      my $sealed = ($text =~ /(?<![-\w])sealed\s+(?:(?:public|abstract|static)\s+)*(?:class|interface)\s+\Q$name\E\b/) ? "yes" : "no";
      my $components = "";
      if ($kind eq "record" && $rest =~ /^\s*(?:<(?:[^<>]++|<[^<>]*>)*>)?\s*(\((?:[^()]++|(?1))*\))/) {
        $components = substr($1, 1, -1);
        $rest = substr($rest, $+[0]);
      }
      1 while $rest =~ s/<[^<>]*>//;
      my @parents;
      while ($rest =~ /\b(?:extends|implements)\s+(.+?)(?=\s+(?:extends|implements|permits)\b|\s*$)/g) {
        push @parents, $1;
      }
      my ($permits) = $rest =~ /\bpermits\s+(.+?)\s*$/;
      $known{$name} = 1 if $scope eq "main";
      push @rows, [$m, $scope, $kind, $name, $sealed, names(join(",", @parents)), names($permits // ""), $components, $exempt, $f];
    }
    for my $r (@rows) {
      my (%seen, @refs);
      for my $w ($r->[7] =~ /[A-Za-z_][A-Za-z0-9_]*/g) {
        push @refs, $w if $known{$w} && !$seen{$w}++;
      }
      $r->[7] = join(",", @refs);
      print join("|", @$r), "\n";
    }
  '
}

# Lines "file:import com.artyomh.finance.<pkg>[.<sub>]" for every project import in one module's source tree.
module_imports() { # module subdir(src/main/java|src/test/java)
  local d
  d="$BACKEND/$(module_dir "$1")/$2"
  if [ -d "$d" ]; then
    grep -rnoE '^import[[:space:]]+(static[[:space:]]+)?com\.artyomh\.finance\.[a-z0-9]+(\.[a-z0-9]+)?' --include='*.java' "$d" 2>/dev/null
  fi
}

# ---------- generate ----------
generate() {
  local scan m rows kind name sealed parents permits refs p stereo line target
  scan=$(scan_types)
  mkdir -p "$OUT_DIR" || return 1
  # Temp file + move: an interrupted hook run leaves the previous good diagram, never half of a new one.
  local landscape_tmp="$OUT_DIR/.type-landscape.md.tmp"
  local deps_tmp="$OUT_DIR/.module-deps.md.tmp"

  {
    echo "# Type landscape"
    echo
    echo "_Generated by graphify — do not edit by hand. Regenerate:_ \`bash .claude/skills/graphify/graphify.sh generate\`"
    for m in $MODULES; do
      rows=$(printf '%s\n' "$scan" | grep "^${m}|main|")
      [ -n "$rows" ] || continue
      echo
      echo "## ${m}"
      echo
      echo '```mermaid'
      echo 'classDiagram'
      echo '    direction LR'
      while IFS='|' read -r _ _ kind name sealed _ _ _ _ _; do
        stereo="$kind"
        if [ "$sealed" = yes ]; then
          stereo="sealed $kind"
        fi
        printf '    class %s { <<%s>> }\n' "$name" "$stereo"
      done <<EOF
$rows
EOF
      while IFS='|' read -r _ _ kind name sealed parents permits refs _ _; do
        for p in ${parents//,/ }; do
          printf '    %s <|-- %s\n' "$p" "$name"
        done
        for p in ${permits//,/ }; do
          case ",$parents," in
            *",$p,"*) ;;
            *) printf '    %s <|-- %s\n' "$name" "$p" ;;
          esac
        done
        for p in ${refs//,/ }; do
          printf '    %s --> %s : has\n' "$name" "$p"
        done
      done <<EOF | sort -u
$rows
EOF
      echo '```'
    done
  } > "$landscape_tmp"
  mv "$landscape_tmp" "$OUT_DIR/type-landscape.md"

  {
    echo "# Module dependencies"
    echo
    echo "_Generated by graphify from actual \`import\` statements in main sources. Allowed direction:_"
    echo "\`${DIRECTION}\` _— never a cycle. A dotted \`violates\` edge is a HARD violation._"
    echo
    echo '```mermaid'
    echo 'flowchart LR'
    for m in $MODULES; do
      echo "    ${m}"
    done
    for m in $MODULES; do
      while IFS= read -r line; do
        [ -n "$line" ] || continue
        target=$(import_target "$line")
        is_module "$target" || continue
        [ "$target" = "$m" ] && continue
        if dep_allowed "$m" "$target"; then
          echo "    ${m} --> ${target}"
        else
          echo "    ${m} -. violates .-> ${target}"
        fi
      done <<EOF
$(module_imports "$m" src/main/java)
EOF
    done | sort -u
    echo '```'
  } > "$deps_tmp"
  mv "$deps_tmp" "$OUT_DIR/module-deps.md"

  echo "graphify: wrote $OUT_DIR/type-landscape.md and $OUT_DIR/module-deps.md"
}

# ---------- check ----------
check() {
  local hard="" warns="" m line file target scan name count kind parents
  local nl='
'

  # 1) HARD: dependency-direction violations, main AND test sources (a test may not reach upstream either;
  #    tests may additionally use the mockdata fixtures).
  local scope
  for m in $MODULES; do
    for scope in main test; do
      while IFS= read -r line; do
        [ -n "$line" ] || continue
        file=${line%%:*}
        target=$(import_target "$line")
        is_module "$target" || continue
        [ "$target" = "$m" ] && continue
        if [ "$scope" = test ]; then
          test_dep_allowed "$m" "$target" && continue
        else
          dep_allowed "$m" "$target" && continue
        fi
        hard="${hard}graphify VIOLATION: ${m} imports ${target} (${file}) — violates ${DIRECTION}${nl}"
      done <<EOF
$(module_imports "$m" "src/$scope/java")
EOF
    done
  done

  scan=$(scan_types)

  # 2) WARN: non-sealed main-source interfaces with 0 or 1 implementors (test doubles count as implementors).
  #    Hexagonal ports (a `port` package) are pre-approved extension points (CLAUDE.md) and exempt.
  while IFS='|' read -r name count; do
    [ -n "$name" ] || continue
    if [ "$count" -eq 0 ]; then
      warns="${warns}graphify warn: interface ${name} has no implementor — dead abstraction? (abstraction-design gate)${nl}"
    elif [ "$count" -eq 1 ]; then
      warns="${warns}graphify warn: interface ${name} has a single implementation — justify via abstraction-design gate #4 or inline it${nl}"
    fi
  done <<EOF
$(printf '%s\n' "$scan" | awk -F'|' '
  NF >= 10 {
    n = split($6, ps, ",")
    for (i = 1; i <= n; i++) { if (ps[i] != "") { impl[ps[i]]++ } }
    if ($2 == "main" && $3 == "interface" && $5 == "no" && $9 == "no" && $10 !~ /\/port\//) { ifs[$4] = 1 }
  }
  END { for (i in ifs) { print i "|" (impl[i] + 0) } }' | sort)
EOF

  # 3) WARN: DTO records (request/response/dto packages) entering hierarchies.
  while IFS='|' read -r _ _ kind name _ parents _ _ _ file; do
    [ "$kind" = "record" ] || continue
    [ -n "$parents" ] || continue
    case "$file" in
      */request/*|*/response/*|*/dto/*)
        warns="${warns}graphify warn: DTO record ${name} implements ${parents} — DTO records stay flat (abstraction-design)${nl}"
        ;;
    esac
  done <<EOF
$(printf '%s\n' "$scan" | grep '|main|')
EOF

  printf '%s' "$hard"
  printf '%s' "$warns"
  if [ -n "$hard" ]; then
    return 2
  fi
  return 0
}

case "${1:-}" in
  generate|check) ;;
  *) echo "usage: graphify.sh generate|check" >&2; exit 1 ;;
esac

has_sources || exit 0

case "$1" in
  generate) generate ;;
  check) check ;;
  *) echo "usage: graphify.sh generate|check" >&2; exit 1 ;;
esac
