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
	     form re-submits them as hidden inputs. One source, two renderings. --%>
	!{StringBuilder tail = new StringBuilder();}
	@for(Map.Entry<String, String> entry : carry.entrySet())
		!{tail.append('&').append(entry.getKey()).append('=')
				.append(java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8));}
	@endfor
	!{if (result.perPage() != DashboardPage.SIZE_DEFAULT) {
		tail.append("&per_page=").append(result.perPage());
	}}
	!{String query = basePath + "?" + pageParam + "=";}
	!{String rest = tail.toString();}
	<nav class="pager-wrap" aria-label="${t.apply("page")}">
		<div class="pager-main">
			<div class="pager-pages">
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
			</div>
			<%-- Glyphs point the RTL way; [dir="ltr"] flips them in app-ui.css,
			     so the same markup reads correctly in both directions. --%>
			<div class="pager-controls">
				@if(current > 1)
					<a href="${query}1${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("first")}" aria-label="${t.apply("first")}">&#187;</a>
					<a href="${query}${current - 1}${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("prev")}" aria-label="${t.apply("prev")}">&#8250;</a>
				@else
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">&#187;</span>
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">&#8250;</span>
				@endif
				@if(current < pages)
					<a href="${query}${current + 1}${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("next")}" aria-label="${t.apply("next")}">&#8249;</a>
					<a href="${query}${pages}${rest}" class="pager-btn pager-btn--nav"
					   title="${t.apply("last")}" aria-label="${t.apply("last")}">&#171;</a>
				@else
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">&#8249;</span>
					<span class="pager-btn pager-btn--nav is-disabled" aria-disabled="true">&#171;</span>
				@endif
			</div>
			<%-- Which rows these are, out of how many. A pager without it makes
			     the reader count. --%>
			<p class="pager-summary">
				${t.apply("pager_showing")}
				<b>${result.from()}</b>&#8211;<b>${result.to()}</b>
				${t.apply("of")}
				<b>${result.total()}</b>
			</p>
		</div>
		<form method="GET" action="${basePath}" class="pager-size">
			@for(Map.Entry<String, String> entry : carry.entrySet())
				<input type="hidden" name="${entry.getKey()}" value="${entry.getValue()}">
			@endfor
			<select name="per_page" class="pager-size-select" onchange="this.form.submit()"
					aria-label="${t.apply("per_page")}">
				@for(int size : new int[] {10, 25, 50, 100})
					<option value="${size}" selected="${size == result.perPage()}">
						${size} ${t.apply("per_page")}
					</option>
				@endfor
			</select>
		</form>
	</nav>
@endif
