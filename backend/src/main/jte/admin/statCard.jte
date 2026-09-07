@import gg.jte.Content
<%-- One number on the home page's grid.
     `href` is optional: a card that leads somewhere is a link, because a count
     of pending requests that cannot be clicked is decoration. The two salary
     totals have no page of their own and stay plain. --%>
@param String value
@param String label
@param String icon
@param String tone = "blue"
@param String href = null
!{Content card = @`
	<div>
		<div class="num">${value}</div>
		<div class="lbl">${label}</div>
	</div>
	<div class="ico ico--${tone}" aria-hidden="true">${icon}</div>
`;}
@if(href == null)
	<div class="home-stat-card">${card}</div>
@else
	<a class="home-stat-card home-stat-card--link" href="${href}">${card}</a>
@endif
