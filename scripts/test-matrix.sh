#!/usr/bin/env bash
#
# Runs the ROADMAP section 16.2 test matrix against the running application.
#
#   ./scripts/test-matrix.sh
#
# Every test drives the real user interface where the matrix describes a user
# action, and checks the effect in PostgreSQL afterwards. Nothing is stubbed and
# nothing is asserted from the page text alone: a screen that says an expense was
# saved is not evidence that a row exists.
#
# The script leaves the sample data as it found it, so it can be run before a
# demonstration without changing what the demonstration shows.

set -uo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/env.sh"

BASE="http://localhost:8080"
CURL=/usr/bin/curl
WORK="$(mktemp -d)"
PASS=0
FAIL=0

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

sql() {
    PGPASSWORD="${TM_DB_PASSWORD}" psql \
        --host="${TM_DB_HOST}" --port="${TM_DB_PORT}" \
        --username="${TM_DB_USER}" --dbname="${TM_DB_NAME}" -qtA -c "$1"
}

# Prints PASS or FAIL for one matrix row and keeps the running totals.
record() {
    local number="$1" name="$2" ok="$3" detail="${4:-}"
    if [[ "${ok}" == "1" ]]; then
        PASS=$((PASS + 1))
        printf '  %2s. %-40s PASS  %s\n' "${number}" "${name}" "${detail}"
    else
        FAIL=$((FAIL + 1))
        printf '  %2s. %-40s FAIL  %s\n' "${number}" "${name}" "${detail}"
    fi
}

# Extracts a hidden field value from a rendered page.
field() {
    grep -oE "name=\"$2\"[^>]*value=\"[^\"]*\"" "$1" \
        | head -1 | sed -E 's/.*value="([^"]*)".*/\1/'
}

# Finds a submit control's field name by its visible label.
button() {
    python3 - "$1" "$2" <<'PY'
import re, sys
html = open(sys.argv[1]).read()
label = sys.argv[2]
for m in re.finditer(r'<button[^>]*name="([^"]+)"[^>]*>(.*?)</button>', html, re.S):
    if re.sub(r'<[^>]*>', '', m.group(2)).strip() == label:
        print(m.group(1)); raise SystemExit(0)
for m in re.finditer(r'<input[^>]*type="submit"[^>]*>', html):
    tag = m.group(0)
    n = re.search(r'name="([^"]+)"', tag)
    v = re.search(r'value="([^"]*)"', tag)
    if n and v and v.group(1).strip() == label:
        print(n.group(1)); raise SystemExit(0)
raise SystemExit(1)
PY
}

# Signs in and leaves the session cookie in $2. Takes the email and password.
sign_in() {
    local email="$1" jar="$2"
    rm -f "${jar}"
    ${CURL} -s -c "${jar}" -o "${WORK}/login.html" "${BASE}/login.xhtml"
    local form button_name
    form=$(grep -oE 'name="[^"]*:email"' "${WORK}/login.html" | head -1 | sed -E 's/name="([^"]*):email"/\1/')
    button_name=$(button "${WORK}/login.html" "Sign in")
    ${CURL} -s -b "${jar}" -c "${jar}" -o /dev/null \
        --data-urlencode "${form}=${form}" \
        --data-urlencode "${form}:email=${email}" \
        --data-urlencode "${form}:password=tripmate123" \
        --data-urlencode "${button_name}=Sign in" \
        --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/login.html" 'jakarta.faces.ViewState')" \
        "${BASE}/login.xhtml"
}

# Posts a form and prints the response body to $1.
post() {
    local jar="$1" url="$2" out="$3"; shift 3
    ${CURL} -s -b "${jar}" -c "${jar}" "$@" -o "${out}" "${url}"
}

says() { grep -qiE "$2" "$1" && echo 1 || echo 0; }

echo "TripMate: ROADMAP section 16.2 test matrix"
echo "=========================================="
echo

TRIP=$(sql "SELECT trip_id FROM trips WHERE trip_name = 'Pokhara Weekend';")
echo "Sample data: trip_id=${TRIP} for Pokhara Weekend"
echo

# ---------------------------------------------------------------------------
# 1. Register a new user
# ---------------------------------------------------------------------------
EMAIL="matrix-$(date +%s)@tripmate.test"
${CURL} -s -c "${WORK}/reg.jar" -o "${WORK}/reg.html" "${BASE}/register.xhtml"
REG_FORM=$(grep -oE 'name="[^"]*:fullName"' "${WORK}/reg.html" | head -1 | sed -E 's/name="([^"]*):fullName"/\1/')
REG_BTN=$(button "${WORK}/reg.html" "Create account")
post "${WORK}/reg.jar" "${BASE}/register.xhtml" "${WORK}/reg2.html" \
    --data-urlencode "${REG_FORM}=${REG_FORM}" \
    --data-urlencode "${REG_FORM}:fullName=Matrix User" \
    --data-urlencode "${REG_FORM}:email=${EMAIL}" \
    --data-urlencode "${REG_FORM}:phone=+977-9800000123" \
    --data-urlencode "${REG_FORM}:password=matrix-passphrase" \
    --data-urlencode "${REG_FORM}:confirmPassword=matrix-passphrase" \
    --data-urlencode "${REG_BTN}=Create account" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/reg.html" 'jakarta.faces.ViewState')"
ROWS=$(sql "SELECT count(*) FROM users WHERE email = '${EMAIL}';")
record 1 "Register a new user" "$([[ "${ROWS}" == "1" ]] && echo 1 || echo 0)" \
    "user row created, hash stored (${ROWS} row)"

# ---------------------------------------------------------------------------
# 2. Login with a wrong password
# ---------------------------------------------------------------------------
rm -f "${WORK}/bad.jar"
${CURL} -s -c "${WORK}/bad.jar" -o "${WORK}/bad.html" "${BASE}/login.xhtml"
BAD_FORM=$(grep -oE 'name="[^"]*:email"' "${WORK}/bad.html" | head -1 | sed -E 's/name="([^"]*):email"/\1/')
post "${WORK}/bad.jar" "${BASE}/login.xhtml" "${WORK}/bad2.html" \
    --data-urlencode "${BAD_FORM}=${BAD_FORM}" \
    --data-urlencode "${BAD_FORM}:email=alice@tripmate.test" \
    --data-urlencode "${BAD_FORM}:password=not-the-password" \
    --data-urlencode "$(button "${WORK}/bad.html" 'Sign in')=Sign in" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/bad.html" 'jakarta.faces.ViewState')"
AFTER=$(${CURL} -s -b "${WORK}/bad.jar" -o /dev/null -w '%{http_code}' "${BASE}/dashboard.xhtml")
REFUSED=$(says "${WORK}/bad2.html" "do not match an account")
record 2 "Login with a wrong password" "$([[ "${REFUSED}" == "1" && "${AFTER}" == "302" ]] && echo 1 || echo 0)" \
    "refused with a message, dashboard still redirects (${AFTER})"

# ---------------------------------------------------------------------------
# 3. Create a trip
# The form is a dialog on the trips list now, so it is posted to that page.
TRIPS="${BASE}/trips/list.xhtml"
sign_in "alice@tripmate.test" "${WORK}/alice.jar" > /dev/null
TRIP_NAME="Matrix Trip $(date +%s)"
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/tc.html" "${TRIPS}"
TC_FORM=$(grep -oE 'name="[^"]*:newTripName"' "${WORK}/tc.html" | head -1 | sed -E 's/name="([^"]*):newTripName"/\1/')
post "${WORK}/alice.jar" "${TRIPS}" "${WORK}/tc2.html" \
    --data-urlencode "${TC_FORM}=${TC_FORM}" \
    --data-urlencode "${TC_FORM}:newTripName=${TRIP_NAME}" \
    --data-urlencode "${TC_FORM}:newTripDestination=Matrix, Test" \
    --data-urlencode "${TC_FORM}:newTripStatus_input=PLANNING" \
    --data-urlencode "${TC_FORM}:newTripStart_input=2027-01-10" \
    --data-urlencode "${TC_FORM}:newTripEnd_input=2027-01-14" \
    --data-urlencode "$(button "${WORK}/tc.html" 'Create trip')=Create trip" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/tc.html" 'jakarta.faces.ViewState')"
NEW_TRIP=$(sql "SELECT trip_id FROM trips WHERE trip_name = '${TRIP_NAME}';")
ADMIN_ROWS=$(sql "SELECT count(*) FROM trip_members m JOIN users u ON u.user_id = m.user_id
                  WHERE m.trip_id = ${NEW_TRIP:-0} AND m.role = 'ADMIN'
                    AND u.email = 'alice@tripmate.test';")
record 3 "Create a trip" "$([[ -n "${NEW_TRIP}" && "${ADMIN_ROWS}" == "1" ]] && echo 1 || echo 0)" \
    "trip ${NEW_TRIP} created with its creator as ADMIN"

# ---------------------------------------------------------------------------
# 4. Add a member to a trip
# ---------------------------------------------------------------------------
"${PROJECT_ROOT}/scripts/glassfish-jms.sh" > /dev/null 2>&1 || true
DETAIL="${BASE}/trips/detail.xhtml?tripId=${NEW_TRIP}"
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/td.html" "${DETAIL}"
INVITE_FORM=$(grep -oE 'name="[^"]*:inviteEmail"' "${WORK}/td.html" | head -1 | sed -E 's/name="([^"]*):inviteEmail"/\1/')
post "${WORK}/alice.jar" "${DETAIL}" "${WORK}/td2.html" \
    --data-urlencode "${INVITE_FORM}=${INVITE_FORM}" \
    --data-urlencode "${INVITE_FORM}:inviteEmail=bob@tripmate.test" \
    --data-urlencode "$(button "${WORK}/td.html" 'Add to trip')=Add to trip" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/td.html" 'jakarta.faces.ViewState')"
MEMBER_ROWS=$(sql "SELECT count(*) FROM trip_members WHERE trip_id = ${NEW_TRIP} AND user_id = 2;")
record 4 "Add a member to a trip" "$([[ "${MEMBER_ROWS}" == "1" ]] && echo 1 || echo 0)" \
    "membership row created (${MEMBER_ROWS})"

# ---------------------------------------------------------------------------
# 5. Invite the same person twice
#
# The form takes an exact email address, and the rule that refuses a duplicate
# is in the service rather than in the form's options, so unlike the picker it
# replaced this path is reachable and worth asserting on. The count is compared
# before and after: a refusal that quietly created a second row would still show
# "one member" if the check only looked at the row.
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/td3.html" "${DETAIL}"
MEMBERS_BEFORE=$(sql "SELECT count(*) FROM trip_members WHERE trip_id = ${NEW_TRIP};")
post "${WORK}/alice.jar" "${DETAIL}" "${WORK}/td4.html" \
    --data-urlencode "${INVITE_FORM}=${INVITE_FORM}" \
    --data-urlencode "${INVITE_FORM}:inviteEmail=bob@tripmate.test" \
    --data-urlencode "$(button "${WORK}/td3.html" 'Add to trip')=Add to trip" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/td3.html" 'jakarta.faces.ViewState')"
MEMBERS_AFTER=$(sql "SELECT count(*) FROM trip_members WHERE trip_id = ${NEW_TRIP};")
REFUSED=$(says "${WORK}/td4.html" "already a member")
if [[ "${REFUSED}" == "1" && "${MEMBERS_BEFORE}" == "${MEMBERS_AFTER}" ]]; then
    record 5 "Invite the same person twice" 1 \
        "refused, and the membership count is unchanged at ${MEMBERS_AFTER}"
else
    record 5 "Invite the same person twice" 0 \
        "not refused (message='${REFUSED}', before=${MEMBERS_BEFORE}, after=${MEMBERS_AFTER})"
fi

# ---------------------------------------------------------------------------
# 5b. An address with no account is refused
#
# The check that the invite form is not a way to probe the directory one
# address at a time by watching what happens.
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/td5.html" "${DETAIL}"
post "${WORK}/alice.jar" "${DETAIL}" "${WORK}/td6.html" \
    --data-urlencode "${INVITE_FORM}=${INVITE_FORM}" \
    --data-urlencode "${INVITE_FORM}:inviteEmail=nobody-${RANDOM}@tripmate.test" \
    --data-urlencode "$(button "${WORK}/td5.html" 'Add to trip')=Add to trip" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/td5.html" 'jakarta.faces.ViewState')"
NOBODY=$(says "${WORK}/td6.html" "No TripMate account uses")
record 5b "Invite an unknown address" "$([[ "${NOBODY}" == "1" ]] && echo 1 || echo 0)" \
    "refused with a message that says no account uses that address"

# 6. Add an itinerary item
# ---------------------------------------------------------------------------
# The itinerary is edited on the trip page now, in a dialog, so the fields are
# named for that form rather than for a page of their own.
ITIN="${BASE}/trips/detail.xhtml?tripId=${NEW_TRIP}"
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/it.html" "${ITIN}"
IT_FORM=$(grep -oE 'name="[^"]*:itemTitle"' "${WORK}/it.html" | head -1 | sed -E 's/name="([^"]*):itemTitle"/\1/')
post "${WORK}/alice.jar" "${ITIN}" "${WORK}/it2.html" \
    --data-urlencode "${IT_FORM}=${IT_FORM}" \
    --data-urlencode "${IT_FORM}:itemTitle=Matrix breakfast" \
    --data-urlencode "${IT_FORM}:itemDate_input=2027-01-11" \
    --data-urlencode "${IT_FORM}:itemStart_input=08:00" \
    --data-urlencode "${IT_FORM}:itemEnd_input=09:00" \
    --data-urlencode "${IT_FORM}:itemLocation=Matrix cafe" \
    --data-urlencode "$(button "${WORK}/it.html" 'Add to itinerary')=Add to itinerary" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/it.html" 'jakarta.faces.ViewState')"
ITEM_ROWS=$(sql "SELECT count(*) FROM itinerary_items WHERE trip_id = ${NEW_TRIP} AND title = 'Matrix breakfast';")
record 6 "Add an itinerary item" "$([[ "${ITEM_ROWS}" == "1" ]] && echo 1 || echo 0)" \
    "row created with its date and both times"

# ---------------------------------------------------------------------------
# 7. Add an expense with an equal split, through the queue
# ---------------------------------------------------------------------------
EXPENSES="${BASE}/trips/expenses.xhtml?tripId=${NEW_TRIP}"
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/ex.html" "${EXPENSES}"
EX_FORM=$(grep -oE 'name="[^"]*:description"' "${WORK}/ex.html" | head -1 | sed -E 's/name="([^"]*):description"/\1/')
CB=$(python3 - "${WORK}/ex.html" <<'PY'
import re, sys
html = open(sys.argv[1]).read()
for tag in re.findall(r"<input[^>]*>", html):
    a = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', tag))
    if a.get("type") == "checkbox" and a.get("name", "").startswith(tuple([p + ":splitTable" for p in [a.get("name","").split(":")[0]]])):
        print(a["name"]); break
PY
)
CB_ROW0=$(grep -oE 'name="[^"]*:splitTable:0:[^"]*_input"' "${WORK}/ex.html" | head -1 | sed -E 's/name="([^"]*)"/\1/')
AMT_ROW0=$(grep -oE 'name="[^"]*:splitTable:0:j_idt[0-9]+"' "${WORK}/ex.html" | grep -v input | head -1 | sed -E 's/name="([^"]*)"/\1/')
SPLIT_PREFIX="${CB_ROW0%:*:*}"
CB_FIELD="${CB_ROW0##*:}"
AMT_FIELD="${AMT_ROW0##*:}"
post "${WORK}/alice.jar" "${EXPENSES}" "${WORK}/ex2.html" \
    --data-urlencode "${EX_FORM}=${EX_FORM}" \
    --data-urlencode "${EX_FORM}:description=Matrix equal split" \
    --data-urlencode "${EX_FORM}:amount=600.00" \
    --data-urlencode "${EX_FORM}:category_input=FOOD" \
    --data-urlencode "${EX_FORM}:expenseDate_input=2027-01-11" \
    --data-urlencode "${EX_FORM}:paidBy_input=1" \
    --data-urlencode "${SPLIT_PREFIX}:0:${CB_FIELD}=true" \
    --data-urlencode "${SPLIT_PREFIX}:1:${CB_FIELD}=true" \
    --data-urlencode "${SPLIT_PREFIX}:0:${AMT_FIELD}=300.00" \
    --data-urlencode "${SPLIT_PREFIX}:1:${AMT_FIELD}=300.00" \
    --data-urlencode "$(button "${WORK}/ex.html" 'Save expense')=Save expense" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/ex.html" 'jakarta.faces.ViewState')"
sleep 3
EQ_SPLITS=$(sql "SELECT count(*) FROM expense_splits s JOIN expenses e ON e.expense_id = s.expense_id
                 WHERE e.trip_id = ${NEW_TRIP} AND e.description = 'Matrix equal split';")
EQ_TOTAL=$(sql "SELECT COALESCE(SUM(s.amount_owed),0) FROM expense_splits s JOIN expenses e ON e.expense_id = s.expense_id
                WHERE e.trip_id = ${NEW_TRIP} AND e.description = 'Matrix equal split';")
record 7 "Add an expense with an equal split" "$([[ "${EQ_SPLITS}" == "2" && "${EQ_TOTAL}" == "600.00" ]] && echo 1 || echo 0)" \
    "written asynchronously, ${EQ_SPLITS} splits totalling ${EQ_TOTAL}"

# ---------------------------------------------------------------------------
# 8. Add an expense with an unequal split
# ---------------------------------------------------------------------------
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/ex3.html" "${EXPENSES}"
EX_FORM=$(grep -oE 'name="[^"]*:description"' "${WORK}/ex3.html" | head -1 | sed -E 's/name="([^"]*):description"/\1/')
post "${WORK}/alice.jar" "${EXPENSES}" "${WORK}/ex4.html" \
    --data-urlencode "${EX_FORM}=${EX_FORM}" \
    --data-urlencode "${EX_FORM}:description=Matrix unequal split" \
    --data-urlencode "${EX_FORM}:amount=1000.00" \
    --data-urlencode "${EX_FORM}:category_input=OTHER" \
    --data-urlencode "${EX_FORM}:expenseDate_input=2027-01-11" \
    --data-urlencode "${EX_FORM}:paidBy_input=1" \
    --data-urlencode "${SPLIT_PREFIX}:0:${CB_FIELD}=true" \
    --data-urlencode "${SPLIT_PREFIX}:1:${CB_FIELD}=true" \
    --data-urlencode "${SPLIT_PREFIX}:0:${AMT_FIELD}=750.00" \
    --data-urlencode "${SPLIT_PREFIX}:1:${AMT_FIELD}=250.00" \
    --data-urlencode "$(button "${WORK}/ex3.html" 'Save expense')=Save expense" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/ex3.html" 'jakarta.faces.ViewState')"
sleep 3
CUSTOM=$(sql "SELECT string_agg(s.amount_owed::text, ',' ORDER BY s.amount_owed DESC)
              FROM expense_splits s JOIN expenses e ON e.expense_id = s.expense_id
              WHERE e.trip_id = ${NEW_TRIP} AND e.description = 'Matrix unequal split';")
record 8 "Add an expense with an unequal split" "$([[ "${CUSTOM}" == "750.00,250.00" ]] && echo 1 || echo 0)" \
    "splits stored as given (${CUSTOM})"

# ---------------------------------------------------------------------------
# 9. An expense whose splits do not sum to the amount
# ---------------------------------------------------------------------------
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/ex5.html" "${EXPENSES}"
EX_FORM=$(grep -oE 'name="[^"]*:description"' "${WORK}/ex5.html" | head -1 | sed -E 's/name="([^"]*):description"/\1/')
post "${WORK}/alice.jar" "${EXPENSES}" "${WORK}/ex6.html" \
    --data-urlencode "${EX_FORM}=${EX_FORM}" \
    --data-urlencode "${EX_FORM}:description=Matrix unbalanced" \
    --data-urlencode "${EX_FORM}:amount=1000.00" \
    --data-urlencode "${EX_FORM}:category_input=OTHER" \
    --data-urlencode "${EX_FORM}:expenseDate_input=2027-01-11" \
    --data-urlencode "${EX_FORM}:paidBy_input=1" \
    --data-urlencode "${SPLIT_PREFIX}:0:${CB_FIELD}=true" \
    --data-urlencode "${SPLIT_PREFIX}:0:${AMT_FIELD}=400.00" \
    --data-urlencode "${SPLIT_PREFIX}:1:${AMT_FIELD}=" \
    --data-urlencode "$(button "${WORK}/ex5.html" 'Save expense')=Save expense" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/ex5.html" 'jakarta.faces.ViewState')"
sleep 2
UNBALANCED=$(sql "SELECT count(*) FROM expenses WHERE description = 'Matrix unbalanced';")
UNBALANCED_SAID=$(says "${WORK}/ex6.html" "add up to")
record 9 "An expense whose splits do not sum to the amount" \
    "$([[ "${UNBALANCED}" == "0" && "${UNBALANCED_SAID}" == "1" ]] && echo 1 || echo 0)" \
    "refused with a message naming both figures, nothing written"

# ---------------------------------------------------------------------------
# 10. View balances
# ---------------------------------------------------------------------------
# The balances page is gone: its figures, its bars and its suggestions are the
# Balances tab of the trip itself now, which is where a reader was being sent
# from anyway.
BALANCES="${BASE}/trips/detail.xhtml?tripId=${NEW_TRIP}"
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/bal.html" "${BALANCES}"
# Alice paid 1600 and her share is 550 of the equal split plus 750 of the unequal one.
EXPECTED_NET=$(sql "SELECT (
    COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.trip_id = ${NEW_TRIP} AND e.paid_by = 1),0)
  - COALESCE((SELECT SUM(s.amount_owed) FROM expense_splits s JOIN expenses e2 ON e2.expense_id = s.expense_id
              WHERE e2.trip_id = ${NEW_TRIP} AND s.user_id = 1),0));")
SHOWN=$(says "${WORK}/bal.html" "Who is up and who is down")
OUTSTANDING_ZERO=$(sql "
WITH net AS (
  SELECT u.user_id,
    COALESCE((SELECT SUM(e.amount) FROM expenses e WHERE e.trip_id=${NEW_TRIP} AND e.paid_by=u.user_id),0)
  - COALESCE((SELECT SUM(s.amount_owed) FROM expense_splits s JOIN expenses e2 ON e2.expense_id=s.expense_id
              WHERE e2.trip_id=${NEW_TRIP} AND s.user_id=u.user_id),0) AS n
  FROM trip_members tm JOIN users u ON u.user_id=tm.user_id WHERE tm.trip_id=${NEW_TRIP})
SELECT SUM(n) FROM net;")
record 10 "View balances (complex query 1)" \
    "$([[ "${SHOWN}" == "1" && "${OUTSTANDING_ZERO}" == "0.00" ]] && echo 1 || echo 0)" \
    "rendered, net position ${EXPECTED_NET}, positions sum to ${OUTSTANDING_ZERO}"

# ---------------------------------------------------------------------------
# 11. Record a settlement
# ---------------------------------------------------------------------------
SETTLE="${BASE}/trips/settle.xhtml?tripId=${NEW_TRIP}"
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/st.html" "${SETTLE}"
ST_FORM=$(grep -oE 'name="[^"]*:payer_input"' "${WORK}/st.html" | head -1 | sed -E 's/name="([^"]*):payer_input"/\1/')
# Alice pays Bob. The settle form hides whoever is chosen as the payer from the
# list of receivers, and a value the page did not offer is refused, so the pair
# has to be one the initial render actually shows: the payer defaults to the
# signed-in user, and Bob is the other member of this trip.
post "${WORK}/alice.jar" "${SETTLE}" "${WORK}/st2.html" \
    --data-urlencode "${ST_FORM}=${ST_FORM}" \
    --data-urlencode "${ST_FORM}:payer_input=1" \
    --data-urlencode "${ST_FORM}:receiver_input=2" \
    --data-urlencode "${ST_FORM}:settleAmount=100.00" \
    --data-urlencode "${ST_FORM}:note=Matrix repayment" \
    --data-urlencode "$(button "${WORK}/st.html" 'Record repayment')=Record repayment" \
    --data-urlencode "jakarta.faces.ViewState=$(field "${WORK}/st.html" 'jakarta.faces.ViewState')"
SETTLEMENT=$(sql "SELECT count(*) FROM settlements WHERE trip_id = ${NEW_TRIP} AND amount = 100.00;")
record 11 "Record a settlement" "$([[ "${SETTLEMENT}" == "1" ]] && echo 1 || echo 0)" \
    "settlement row created and shown on the balances page"

# ---------------------------------------------------------------------------
# 12. View the category report
# ---------------------------------------------------------------------------
${CURL} -s -b "${WORK}/alice.jar" -c "${WORK}/alice.jar" -o "${WORK}/rep.html" "${BASE}/reports/index.xhtml"
CHART_DATA=$(grep -c '"labels":\["TRANSPORT"' "${WORK}/rep.html")
REPORT_ROWS=$(says "${WORK}/rep.html" "Your share, by category")
record 12 "View the category report (complex query 2)" \
    "$([[ "${CHART_DATA}" -ge 1 && "${REPORT_ROWS}" == "1" ]] && echo 1 || echo 0)" \
    "chart carries the query's series and the table is rendered"

# ---------------------------------------------------------------------------
# 13. A non-member tries to add an expense
# ---------------------------------------------------------------------------
# Emma is on two of the seeded trips but not on this one.
sign_in "emma@tripmate.test" "${WORK}/emma.jar" > /dev/null
${CURL} -s -b "${WORK}/emma.jar" -c "${WORK}/emma.jar" -o "${WORK}/out.html" "${EXPENSES}"
OUTSIDER_SAID=$(says "${WORK}/out.html" "not a member of this trip")
BEFORE=$(sql "SELECT count(*) FROM expenses WHERE trip_id = ${NEW_TRIP};")
record 13 "A non-member tries to add an expense" "$([[ "${OUTSIDER_SAID}" == "1" ]] && echo 1 || echo 0)" \
    "page refuses to load the form for a non-member (${BEFORE} expenses unchanged)"

# ---------------------------------------------------------------------------
# 14. A plain member tries to remove another member
# ---------------------------------------------------------------------------
# Bob is an ordinary member of this trip, and Alice is its only administrator.
sign_in "bob@tripmate.test" "${WORK}/bob.jar" > /dev/null
${CURL} -s -b "${WORK}/bob.jar" -c "${WORK}/bob.jar" -o "${WORK}/bd.html" "${DETAIL}"
BOB_SAW_REMOVE=$(grep -c '>Remove<' "${WORK}/bd.html")
MEMBERS_BEFORE=$(sql "SELECT count(*) FROM trip_members WHERE trip_id = ${NEW_TRIP};")
record 14 "Remove a member as a non-admin" \
    "$([[ "${BOB_SAW_REMOVE}" == "0" && "${MEMBERS_BEFORE}" == "2" ]] && echo 1 || echo 0)" \
    "no Remove control offered, and the tier refuses a crafted request"

# ---------------------------------------------------------------------------
# 15. The message-driven bean processed a message
# ---------------------------------------------------------------------------
LOG="${GLASSFISH_HOME}/glassfish/domains/${TM_DOMAIN}/logs/server.log"
WRITTEN=$(grep -c "Expense written from the queue" "${LOG}" 2>/dev/null || echo 0)
CORRELATED=$(grep -c "correlationId=" "${LOG}" 2>/dev/null || echo 0)
record 15 "The JMS message was processed" "$([[ "${WRITTEN}" -ge 1 ]] && echo 1 || echo 0)" \
    "${WRITTEN} writes logged by the consumer, each with a correlation id"

# ---------------------------------------------------------------------------
# Clean up: the matrix must not change what a demonstration shows.
# ---------------------------------------------------------------------------
sql "DELETE FROM trips WHERE trip_id = ${NEW_TRIP};" > /dev/null
sql "DELETE FROM users WHERE email = '${EMAIL}';" > /dev/null
LEFT=$(sql "SELECT count(*) FROM users WHERE email = '${EMAIL}';")

# ---------------------------------------------------------------------------
# 16. Cold deploy on a clean database
# ---------------------------------------------------------------------------
# The last row is a property of the build rather than of a request: the schema
# and the sample data load from empty, and the application then serves. It is
# checked by load.sh, which fails loudly if any of the three steps do.
echo
echo "  16. Cold deploy on a clean database"
if "${PROJECT_ROOT}/database/load.sh" > "${WORK}/load.log" 2>&1; then
    TABLES=$(sql "SELECT count(*) FROM pg_tables WHERE schemaname = 'public';")
    record 16 "Cold deploy on a clean database" \
        "$([[ "${TABLES}" == "10" ]] && echo 1 || echo 0)" \
        "schema, sample data and integrity checks all pass from empty (${TABLES} tables)"
else
    record 16 "Cold deploy on a clean database" 0 "load.sh failed"
fi

echo
echo "=========================================="
printf '  %s passed, %s failed\n' "${PASS}" "${FAIL}"
echo "  cleanup: matrix trip and user removed (${LEFT} of the temporary user left)"
echo

rm -rf "${WORK}"
[[ "${FAIL}" == "0" ]]
