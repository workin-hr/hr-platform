@import java.util.function.Function
@param String status
@param Function<String, String> t
<%-- badge() (includes/layout.php:77-107): one status, one colour and one label, with an unknown
     status shown as itself in grey. Every list that shows a status goes through this, so no page
     can invent a colour of its own -- pending is yellow and inactive is grey, as legacy has them
     (D-255). --%>
!{String colour = switch (status == null ? "" : status) {
	case "active", "approved", "finalized", "done", "paid", "qr", "hr", "1" -> "badge-green";
	case "pending", "draft", "open", "excel", "manager" -> "badge-yellow";
	case "rejected" -> "badge-red";
	case "in_review", "male", "app", "company_admin" -> "badge-blue";
	case "female" -> "badge-pink";
	default -> "badge-gray";
};}
!{String key = switch (status == null ? "" : status) {
	case "1" -> "yes";
	case "0" -> "no";
	case "male", "female" -> "gender_" + status;
	case "app", "excel", "qr" -> "method_" + status;
	case "company_admin" -> "role_admin";
	case "hr", "manager", "employee" -> "role_" + status;
	case "active", "pending", "rejected", "suspended", "approved", "draft", "finalized",
			"open", "in_review", "closed", "done", "paid", "inactive" -> "status_" + status;
	default -> null;
};}
<span class="badge ${colour}">${key == null ? status : t.apply(key)}</span>
