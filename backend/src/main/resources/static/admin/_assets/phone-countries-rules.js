// window.WorkinPhoneCountriesRules: each active country's number length and
// prefixes, which phone-validator.js judges a number by.
//
// Legacy's layout assigns it in an inline script. Here the JSON comes on this
// tag's data-rules, where the template escapes it as an attribute value, and
// the page carries no inline script.
window.WorkinPhoneCountriesRules = JSON.parse(document.currentScript.getAttribute('data-rules'));
