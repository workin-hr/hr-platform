@import gg.jte.Content
@import com.workin.backend.platformadmin.web.AdminIcons
<%-- One number on the home page's grid.
     `href` is optional: a card that leads somewhere is a link, because a count
     of pending requests that cannot be clicked is decoration. The two salary
     totals have no page of their own and stay plain. --%>
@param String value
@param String label
<%-- `icon` names a glyph in AdminIcons, not a character. An emoji ignores the
     tile's own `color`, renders as a different drawing on every platform, and
     sits beside a sidebar of stroked SVGs looking like it came from somewhere
     else. AdminIcons.of answers "" for a name it does not know, so
     everyStatCardNamesAnIconTheRegistryDefines is what keeps a typo from
     shipping an empty tile. --%>
@param String icon
@param String tone = "blue"
@param String href = null
<%-- A money total: on a phone's two-column grid it takes the whole row, since
     "27,643,220 ج.م" does not fit half of 390px and wrapping a figure from its
     currency is worse. --%>
@param boolean wide = false
<%-- A second line under the label: what the number is out of, e.g. today's
     attendance as a share of the headcount (D-290). --%>
@param String note = null
<%-- A queue with nothing in it. The "needs action" cards are the ones an
     administrator scans for work, so an empty one steps back rather than
     drawing the same red or amber tile as a queue of fifty. --%>
@param boolean quiet = false
!{Content card = @`
	<div>
		<div class="num">${value}</div>
		<div class="lbl">${label}</div>
		@if(note != null)
			<div class="note">${note}</div>
		@endif
	</div>
	<div class="ico ico--${tone}" aria-hidden="true">$unsafe{AdminIcons.of(icon, "stat-icon")}</div>
`;}
@if(href == null)
	<div class="home-stat-card${wide ? " home-stat-card--wide" : ""}${quiet ? " home-stat-card--quiet" : ""}">${card}</div>
@else
	<a class="home-stat-card home-stat-card--link${wide ? " home-stat-card--wide" : ""}${quiet ? " home-stat-card--quiet" : ""}" href="${href}">${card}</a>
@endif
