@import java.util.function.Function
@param Function<String, String> t
<%-- tableStart() (includes/layout.php:12-18): the actions column is 52px wide, so legacy gives it
     the label as a title and a screen-reader-only span rather than visible text, which would clip
     and push the table into a horizontal scroll (D-255). --%>
<th class="col-actions" title="${t.apply("actions")}"><span class="sr-only">${t.apply("actions")}</span></th>
