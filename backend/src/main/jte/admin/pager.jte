@import java.util.Map
@import java.util.function.Function
@import com.workin.backend.platformadmin.web.DashboardPage
<%-- `pagerHtml()` (dashboard/includes/query.php), as a template rather than as
     a loop repeated on every list page.

     Sixteen pages each rendered their own `<div class="pager">` with one link
     per page and a class name no stylesheet defines -- so a 3,783-employee
     list printed 378 unstyled numbers. app-ui.css has carried the real
     component all along (.pager-wrap / .pager-btn / .pager-dots /
     .pager-size); nothing used it.

     Three deliberate improvements over the PHP, none of them behavioural:
     first/previous buttons beside next/last, a "showing 1-10 of 486" summary,
     and `per_page` carried in the page links -- legacy drops it, so choosing
     100 per page and then turning the page silently returned you to 10. --%>
@param String basePath
@param DashboardPage<?> result
@param Map<String, String> carry
@param Function<String, String> t
@param String pageParam = "page"
@param int windowSize = 5
@if(result.total() > 0)
	!{int current = Math.max(1, result.page());}
	!{int pages = Math.max(1, result.pages());}
	!{int half = windowSize / 2;}
	!{int start = Math.max(1, Math.min(current - half, pages - windowSize + 1));}
	!{int end = Math.min(pages, start + windowSize - 1);}
	<%-- Built once: every link below carries the same filters, and the size
	     form re-submits them as hidden inputs. One source, two renderings.

	     A caller with two pagers on one page (attendance) can carry the
	     other one's current page in `carry`; skip our own pageParam there,
	     as query.php's pagerHtml() unsets only its own -- otherwise the
	     link below would carry it twice. --%>
	!{StringBuilder tail = new StringBuilder();}
	@for(Map.Entry<String, String> entry : carry.entrySet())
		@if(!entry.getKey().equals(pageParam))
			!{tail.append('&').append(entry.getKey()).append('=')
					.append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));}
		@endif
	@endfor
	!{if (result.perPage() != DashboardPage.SIZE_DEFAULT) {
		tail.append("&per_page=").append(result.perPage());
	}}
	!{String query = basePath + "?" + pageParam + "=";}
	!{String rest = tail.toString();}
	<%-- Chevrons are drawn pointing the RTL way (previous points right, next
	     points left); [dir="ltr"] mirrors them in app-ui.css. First and
	     previous sit before page 1 and next and last after the final page, in
	     one row, so each control is beside the end of the range it moves
	     toward -- in either direction. --%>
	!{String prevIcon = "<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M9 18l6-6-6-6\"/></svg>";}
	!{String firstIcon = "<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M13 17l5-5-5-5M6 17l5-5-5-5\"/></svg>";}
	!{String nextIcon = "<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M15 18l-6-6 6-6\"/></svg>";}
	!{String lastIcon = "<svg viewBox=\"0 0 24 24\" width=\"18\" height=\"18\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M11 17l-5-5 5-5M18 17l-5-5 5-5\"/></svg>";}
	<nav class="pager-wrap" aria-label="${t.apply("page")}">
		<div class="pager-main">
			<div class="pager-pages">
				@if(current > 1)
					<a href="${query}1${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("first")}" aria-label="${t.apply("first")}">$unsafe{firstIcon}</a>
					<a href="${query}${current - 1}${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("prev")}" aria-label="${t.apply("prev")}">$unsafe{prevIcon}</a>
				@else
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">$unsafe{firstIcon}</span>
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">$unsafe{prevIcon}</span>
				@endif
				@if(start > 2)
					<a href="${query}1${rest}" class="pager-btn">1</a>
					<span class="pager-dots" aria-hidden="true">…</span>
				@elseif(start == 2)
					<a href="${query}1${rest}" class="pager-btn">1</a>
				@endif
				@for(int page = start; page <= end; page++)
					@if(page == current)
						<span class="pager-btn is-current" aria-current="page">${page}</span>
					@else
						<a href="${query}${page}${rest}" class="pager-btn">${page}</a>
					@endif
				@endfor
				@if(end < pages - 1)
					<span class="pager-dots" aria-hidden="true">…</span>
					<a href="${query}${pages}${rest}" class="pager-btn">${pages}</a>
				@elseif(end == pages - 1 && pages > 1)
					<a href="${query}${pages}${rest}" class="pager-btn">${pages}</a>
				@endif
				@if(current < pages)
					<a href="${query}${current + 1}${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("next")}" aria-label="${t.apply("next")}">$unsafe{nextIcon}</a>
					<a href="${query}${pages}${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("last")}" aria-label="${t.apply("last")}">$unsafe{lastIcon}</a>
				@else
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">$unsafe{nextIcon}</span>
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">$unsafe{lastIcon}</span>
				@endif
			</div>
			<%-- Which rows these are, out of how many. A pager without it makes
			     the reader count.

			     The range is shown only when the page has rows. dbPaginate()
			     takes the offset from the *requested* page and clamps only the
			     reported page number (DashboardPage's own note), so ?page=99 of
			     a three-page list returns nothing while `from` stays at 981 --
			     which printed "showing 981-22 of 22" under an empty table. The
			     total is still worth saying, so only the range drops. --%>
			<p class="pager-summary">
				@if(!result.isEmpty())
					${t.apply("pager_showing")}
					<b>${result.from()}</b>&#8211;<b>${result.to()}</b>
					${t.apply("of")}
				@endif
				<b>${result.total()}</b>
			</p>
		</div>
		<form method="GET" action="${basePath}" class="pager-size">
			<%-- Changing the size re-pages from 1, as it always has (no "page" hidden input);
			     the other pager's own page, if `carry` holds one, still rides along. --%>
			@for(Map.Entry<String, String> entry : carry.entrySet())
				@if(!entry.getKey().equals(pageParam))
					<input type="hidden" name="${entry.getKey()}" value="${entry.getValue()}">
				@endif
			@endfor
			<%-- The server accepts any size from 1 to PAGE_SIZE_MAX, so a
			     bookmarked ?per_page=200 served 200 rows while the box read
			     "10 per page" -- no option matched, and the browser fell back
			     to the first. The active size joins the presets when it is not
			     one of them, in order, so the control always says what is on
			     the screen. --%>
			!{java.util.List<Integer> sizes = new java.util.ArrayList<>(
					java.util.List.of(10, 25, 50, 100));}
			!{if (!sizes.contains(result.perPage())) {
				sizes.add(result.perPage());
				java.util.Collections.sort(sizes);
			}}
			<select name="per_page" class="pager-size-select" onchange="this.form.submit()"
					aria-label="${t.apply("per_page")}">
				@for(int size : sizes)
					<option value="${size}" selected="${size == result.perPage()}">
						${size} ${t.apply("per_page")}
					</option>
				@endfor
			</select>
		</form>
	</nav>
@endif
