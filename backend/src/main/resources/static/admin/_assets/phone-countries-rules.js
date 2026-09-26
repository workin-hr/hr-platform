// window.WorkinPhoneCountriesRules: each active country's number length and
// prefixes from phone_countries -- display data only. Validity is the
// server's libphonenumber check (D-291, ADR-0020); phone-validator.js does
// not judge a number by these.
//
// Legacy's layout assigns it in an inline script. Here the JSON comes on this
// tag's data-rules, where the template escapes it as an attribute value, and
// the page carries no inline script.
window.WorkinPhoneCountriesRules = JSON.parse(document.currentScript.getAttribute('data-rules'));
