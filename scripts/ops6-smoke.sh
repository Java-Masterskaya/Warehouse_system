#!/usr/bin/env bash

set -Eeuo pipefail

dotenv_public_port=""
dotenv_server_port=""
if [[ -f .env ]]; then
    while IFS='=' read -r key value; do
        value="${value%$'\r'}"
        case "$key" in
            PUBLIC_HTTP_PORT)
                dotenv_public_port="$value"
                ;;
            SERVER_PORT)
                dotenv_server_port="$value"
                ;;
        esac
    done < .env
fi

resolved_public_port="${PUBLIC_HTTP_PORT:-${dotenv_public_port:-${SERVER_PORT:-${dotenv_server_port:-8080}}}}"
BASE_URL="${OPS6_BASE_URL:-http://localhost:$resolved_public_port}"
MAX_ATTEMPTS="${OPS6_ATTEMPTS:-8}"

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is missing: $1"
}

read_secret_if_missing() {
    if [[ -z "${OPS6_USERNAME:-}" ]]; then
        [[ -t 0 ]] || fail "Set OPS6_USERNAME for a non-interactive run"
        read -r -p "Username: " OPS6_USERNAME
    fi

    if [[ -z "${OPS6_PASSWORD:-}" ]]; then
        [[ -t 0 ]] || fail "Set OPS6_PASSWORD for a non-interactive run"
        read -r -s -p "Password: " OPS6_PASSWORD
        printf '\n' >&2
    fi
}

header_value() {
    local header_name="$1"
    local header_file="$2"

    awk -v wanted="$header_name" '
        BEGIN {
            wanted = tolower(wanted)
        }
        {
            line = $0
            sub(/\r$/, "", line)
            separator = index(line, ":")
            if (separator == 0) {
                next
            }
            name = tolower(substr(line, 1, separator - 1))
            if (name == wanted) {
                value = substr(line, separator + 1)
                sub(/^[[:space:]]+/, "", value)
                found = value
            }
        }
        END {
            print found
        }
    ' "$header_file"
}

assert_no_set_cookie() {
    local header_file="$1"
    local request_name="$2"

    if awk '
        {
            line = tolower($0)
            sub(/\r$/, "", line)
            if (line ~ /^set-cookie[[:space:]]*:/) {
                found = 1
            }
        }
        END {
            exit found ? 0 : 1
        }
    ' "$header_file"; then
        fail "$request_name returned Set-Cookie; server-side session behavior is not allowed"
    fi
}

require_command curl
require_command awk
require_command find
require_command python3

[[ "$MAX_ATTEMPTS" =~ ^[1-9][0-9]*$ ]] || fail "OPS6_ATTEMPTS must be a positive integer"
if [[ -z "${OPS6_BASE_URL:-}" ]]; then
    [[ "$resolved_public_port" =~ ^[1-9][0-9]*$ ]] \
        || fail "PUBLIC_HTTP_PORT must be a positive integer"
fi
read_secret_if_missing

umask 077
temporary_directory="$(mktemp -d)"
logout_payload=""
cleanup() {
    local exit_status=$?
    trap - EXIT INT TERM
    set +e
    if [[ -n "$logout_payload" && -f "$logout_payload" ]]; then
        curl --silent --show-error \
            --request POST \
            --header 'Content-Type: application/json' \
            --data-binary "@$logout_payload" \
            "$BASE_URL/api/auth/logout" \
            >/dev/null 2>&1
    fi
    if [[ -n "$temporary_directory" && -d "$temporary_directory" ]]; then
        find "$temporary_directory" -mindepth 1 -maxdepth 1 -type f -delete
        rmdir -- "$temporary_directory"
    fi
    exit "$exit_status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

login_payload="$temporary_directory/login.json"
login_headers="$temporary_directory/login.headers"
login_body="$temporary_directory/login.body"
auth_header="$temporary_directory/auth.header"
logout_payload="$temporary_directory/logout.json"

OPS6_LOGIN_USERNAME="$OPS6_USERNAME" OPS6_LOGIN_PASSWORD="$OPS6_PASSWORD" \
    python3 -c '
import json
import os
import sys

json.dump(
    {
        "username": os.environ["OPS6_LOGIN_USERNAME"],
        "password": os.environ["OPS6_LOGIN_PASSWORD"],
    },
    sys.stdout,
)
' > "$login_payload"

printf 'Waiting for nginx at %s\n' "$BASE_URL"
nginx_ready=false
for _ in {1..60}; do
    if curl --silent --show-error --fail "$BASE_URL/nginx-health" >/dev/null 2>&1; then
        nginx_ready=true
        break
    fi
    sleep 1
done
[[ "$nginx_ready" == true ]] || fail "nginx did not become ready"

printf 'Waiting for an application replica at %s\n' "$BASE_URL"
login_status=""
for _ in {1..60}; do
    login_status="$(
        curl --silent --show-error \
            --output "$login_body" \
            --dump-header "$login_headers" \
            --write-out '%{http_code}' \
            --request POST \
            --header 'Content-Type: application/json' \
            --data-binary "@$login_payload" \
            "$BASE_URL/api/auth/login" \
            || true
    )"

    case "$login_status" in
        200)
            break
            ;;
        000|502|503|504)
            sleep 1
            ;;
        *)
            fail "Login returned HTTP $login_status"
            ;;
    esac
done
[[ "$login_status" == "200" ]] || fail "No application replica became ready for login"
assert_no_set_cookie "$login_headers" "Login"

python3 -c '
import json
import sys

with open(sys.argv[1], encoding="utf-8") as response_file:
    response = json.load(response_file)

token = response.get("accessToken")
refresh_token = response.get("refreshToken")
if (
    not isinstance(token, str)
    or not token
    or not isinstance(refresh_token, str)
    or not refresh_token
):
    raise SystemExit(1)

with open(sys.argv[2], "w", encoding="utf-8") as logout_file:
    json.dump(
        {
            "accessToken": token,
            "refreshToken": refresh_token,
        },
        logout_file,
    )

sys.stdout.write(f"Authorization: Bearer {token}\n")
' "$login_body" "$logout_payload" > "$auth_header" \
    || fail "Login response does not contain an access token"

login_instance="$(header_value 'X-Warehouse-Instance' "$login_headers")"
login_upstream="$(header_value 'X-Warehouse-Upstream' "$login_headers")"
[[ -n "$login_instance" ]] || fail "Login response has no X-Warehouse-Instance header"
[[ -n "$login_upstream" ]] || fail "Login response has no X-Warehouse-Upstream header"

declare -A seen_instances=(["$login_instance"]=1)
declare -A seen_upstreams=(["$login_upstream"]=1)
cross_instance_jwt=false

for ((attempt = 1; attempt <= MAX_ATTEMPTS; attempt++)); do
    request_headers="$temporary_directory/request-$attempt.headers"
    request_body="$temporary_directory/request-$attempt.body"

    request_status="$(
        curl --silent --show-error \
            --output "$request_body" \
            --dump-header "$request_headers" \
            --write-out '%{http_code}' \
            --header "@$auth_header" \
            "$BASE_URL/api/items?size=1"
    )"
    [[ "$request_status" == "200" ]] || fail "Authenticated request $attempt returned HTTP $request_status"
    assert_no_set_cookie "$request_headers" "Authenticated request $attempt"

    request_instance="$(header_value 'X-Warehouse-Instance' "$request_headers")"
    request_upstream="$(header_value 'X-Warehouse-Upstream' "$request_headers")"
    [[ -n "$request_instance" ]] || fail "Authenticated response has no X-Warehouse-Instance header"
    [[ -n "$request_upstream" ]] || fail "Authenticated response has no X-Warehouse-Upstream header"

    seen_instances["$request_instance"]=1
    seen_upstreams["$request_upstream"]=1
    if [[ "$request_instance" != "$login_instance" ]]; then
        cross_instance_jwt=true
    fi
done

(( ${#seen_instances[@]} >= 2 )) || fail "Requests reached only one instance; round-robin was not observed"
(( ${#seen_upstreams[@]} >= 2 )) || fail "Requests reached only one upstream address"
[[ "$cross_instance_jwt" == true ]] || fail "JWT was not verified on an instance different from login"

printf 'PASS: login instance: %s\n' "$login_instance"
printf 'PASS: authenticated requests reached %d instances and %d upstream addresses\n' \
    "${#seen_instances[@]}" "${#seen_upstreams[@]}"
printf 'PASS: JWT works across replicas and no sticky session was observed\n'
printf 'PASS: no response attempted to create a server-side session cookie\n'
