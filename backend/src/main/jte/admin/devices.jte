@import java.util.List
@import java.util.Map
@import java.util.function.Function
@import java.util.function.Supplier
@import com.workin.backend.platformadmin.org.ActiveCompanies
@import com.workin.backend.platformadmin.web.AdminDeviceView
@import com.workin.backend.platformadmin.web.DashboardListFilters
@import com.workin.backend.platformadmin.web.DashboardPage
@import com.workin.backend.platformadmin.web.DashboardSession
@import com.workin.devices.agent.DeviceAgent
@import com.workin.devices.agent.DeviceAgentService
@import com.workin.devices.registry.AttendanceDevice
@param String csrfParameterName
@param String csrfToken
@param String currentAdminPhone
@param DashboardListFilters filters
@param Supplier<List<ActiveCompanies.CompanyOption>> companyFilterOptions
@param List<ActiveCompanies.CompanyOption> companyOptions
@param boolean actionsEnabled
@param int liveSeconds
@param String allocateSerial = ""
@param String errorKey = null
@param DeviceAgentService.IssuedAgent issuedAgent = null
@param AttendanceDevice selected = null
@param Map<String, Object> selectedRow = java.util.Map.of()
@param DashboardPage<Map<String, Object>> deviceResult
@param DashboardPage<Map<String, Object>> sightingResult
@param DashboardPage<DeviceAgent> agentResult
@param List<Map<String, Object>> branches
@param DashboardPage<Map<String, Object>> punchResult
@param List<Map<String, Object>> counts
@param DashboardPage<Map<String, Object>> malformedResult
@param int devPage
@param int sightPage
@param int agentPage
@param int punchPage
@param int malPage
@param String currentPage
@param String lang
@param DashboardSession session
@param Function<String, String> t
@param String flash = null
@param String flashType = "success"
@param Function<String, Boolean> available
<%-- Five tables, five page numbers, one size. Each pager appends the other
     four pages to its links (pager.jte skips whichever one names its own
     pageParam), so turning one table leaves the rest where they were --
     attendance.jte's two-pager rule, at five.

     `device` and `live` ride along too: without them a pager link would leave
     the terminal you were looking at, or silently stop the live refresh. The
     requested page numbers are carried, not the clamped ones, for the reason
     attendance.jte gives. `error` is deliberately absent -- a flash replayed
     by every page link is a message that will not go away. --%>
!{java.util.SequencedMap<String, String> carry =
		new java.util.LinkedHashMap<>(filters.asQueryParameters());}
!{if (selected != null) { carry.put("device", String.valueOf(selected.id())); }}
!{if (liveSeconds > 0) { carry.put("live", "1"); }}
!{if (!allocateSerial.isEmpty()) { carry.put("serial", allocateSerial); }}
!{carry.put("dev_page", String.valueOf(devPage));}
!{carry.put("sight_page", String.valueOf(sightPage));}
!{carry.put("agent_page", String.valueOf(agentPage));}
!{carry.put("punch_page", String.valueOf(punchPage));}
!{carry.put("mal_page", String.valueOf(malPage));}
<%-- No PHP counterpart: the dashboard never had devices. Built from the list
     and form vocabulary the org pages already style, so nothing here needs a
     stylesheet of its own. --%>
@template.admin.layout(
	title = t.apply("nav_devices"),
	csrfParameterName = csrfParameterName,
	csrfToken = csrfToken,
	currentAdminPhone = currentAdminPhone,
	currentPage = currentPage,
	lang = lang,
	session = session,
	t = t,
	flash = flash,
	flashType = flashType,
	available = available,
	pageStyles = java.util.List.of("hr-pages.css", "org-form.css"),
	pageScripts = java.util.List.of("devices-live.js"),
	body = @`
		<div data-live-seconds="${liveSeconds}"></div>
		@if(errorKey != null)
			<div class="flash flash-error">${t.apply(errorKey)}</div>
		@endif
		@if(!actionsEnabled)
			<div class="flash flash-warning">${t.apply("admin_actions_disabled")}</div>
		@endif

		@if(selected == null)
			<div class="toolbar page-toolbar page-toolbar-filters">
				<form method="GET" class="toolbar-form toolbar-form--labeled">
					@template.admin.companyFilterField(companyId = filters.companyId(), companies = companyFilterOptions, t = t)
					<button type="submit" class="btn btn-blue">${t.apply("search")}</button>
				</form>
				@if(liveSeconds > 0)
					<a href="/admin/devices" class="btn btn-gray">${t.apply("device_live_stop")}</a>
				@else
					<a href="/admin/devices?live=1" class="btn btn-outline">${t.apply("device_live_start")}</a>
				@endif
			</div>

			@if(issuedAgent != null)
				<div class="data-table-card">
					<div class="data-table-head">
						<h2 class="data-table-title">${t.apply("device_agent_token_title")} — ${issuedAgent.agent().name()}</h2>
					</div>
					<p class="text-muted">${t.apply("device_agent_token_once")}</p>
					<div class="form-row">
						<label for="issued_token">${t.apply("device_agent_token")}</label>
						<input type="text" id="issued_token" readonly data-issued-token value="${issuedAgent.token()}">
					</div>
				</div>
			@endif

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("device_sightings")} (${sightingResult.total()})</h2>
				</div>
				<p class="text-muted">${t.apply("device_sightings_hint")}</p>
				<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("device_serial")}</th>
							<th>${t.apply("device_type")}</th>
							<th>${t.apply("device_push_version")}</th>
							<th>${t.apply("device_first_seen")}</th>
							<th>${t.apply("device_last_seen")}</th>
							<th>${t.apply("device_ip")}</th>
							<th class="col-center">${t.apply("device_hits")}</th>
							<th></th>
						</tr>
					</thead>
					<tbody>
						@for(Map<String, Object> row : sightingResult.data())
							<tr>
								<td class="bold">${AdminDeviceView.text(row, "serial_number")}</td>
								<td>${AdminDeviceView.text(row, "device_type")}</td>
								<td>${AdminDeviceView.text(row, "push_version")}</td>
								<td>${AdminDeviceView.text(row, "first_seen_at")}</td>
								<td>${AdminDeviceView.text(row, "last_seen_at")}</td>
								<td class="text-muted">${AdminDeviceView.text(row, "last_seen_ip")}</td>
								<td class="col-center"><span class="badge badge-gray">${AdminDeviceView.text(row, "hit_count")}</span></td>
								<td><a class="btn btn-outline btn-sm" href="/admin/devices?serial=${AdminDeviceView.text(row, "serial_number")}#allocate">${t.apply("device_allocate")}</a></td>
							</tr>
						@endfor
						@if(sightingResult.isEmpty())
							<tr><td colspan="8" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
			</div>
			@template.admin.pager(basePath = "/admin/devices", result = sightingResult,
					carry = carry, t = t, pageParam = "sight_page")

			<div class="data-table-card" id="allocate">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("device_allocate")}</h2>
				</div>
				<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="allocate">
					<div class="org-form-grid">
						<div class="form-row">
							<label for="serial_number">${t.apply("device_serial")}</label>
							<input type="text" id="serial_number" name="serial_number" required maxlength="64" value="${allocateSerial}">
						</div>
						<div class="form-row">
							<label for="vendor">${t.apply("device_vendor")}</label>
							<select id="vendor" name="vendor">
								<option value="zkteco">ZKTeco</option>
								<option value="hikvision">Hikvision</option>
							</select>
						</div>
						<div class="form-row">
							<label for="device_name">${t.apply("device_name")}</label>
							<input type="text" id="device_name" name="name" required maxlength="255">
						</div>
						<div class="form-row">
							<label for="device_time_zone">${t.apply("device_time_zone")}</label>
							<input type="text" id="device_time_zone" name="device_time_zone" maxlength="64" placeholder="Africa/Cairo">
						</div>
						<div class="form-row org-form-span-2">
							<label for="branch_id">${t.apply("branch")}</label>
							<select id="branch_id" name="branch_id" required>
								<option value="">—</option>
								@for(Map<String, Object> branch : branches)
									<option value="${AdminDeviceView.id(branch, "id")}">${AdminDeviceView.text(branch, "company_name")} — ${AdminDeviceView.text(branch, "name")}</option>
								@endfor
							</select>
						</div>
					</div>
					<div class="form-footer">
						@if(actionsEnabled)
							<button type="submit" class="btn btn-blue">${t.apply("device_allocate")}</button>
						@endif
					</div>
				</form>
			</div>

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("nav_devices")} (${deviceResult.total()})</h2>
				</div>
				<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("company")}</th>
							<th>${t.apply("branch")}</th>
							<th>${t.apply("device_name")}</th>
							<th>${t.apply("device_serial")}</th>
							<th>${t.apply("device_vendor")}</th>
							<th>${t.apply("device_model")}</th>
							<th>${t.apply("device_last_seen")}</th>
							<th>${t.apply("device_last_punch")}</th>
							<th>${t.apply("status")}</th>
							<th></th>
						</tr>
					</thead>
					<tbody>
						@for(Map<String, Object> row : deviceResult.data())
							<tr>
								<td class="text-muted">${AdminDeviceView.text(row, "company_name")}</td>
								<td>${AdminDeviceView.text(row, "branch_name")}</td>
								<td class="bold">${AdminDeviceView.text(row, "name")}</td>
								<td>${AdminDeviceView.text(row, "serial_number")}</td>
								<td>${AdminDeviceView.text(row, "vendor")}</td>
								<td>${AdminDeviceView.text(row, "model")} / ${AdminDeviceView.text(row, "firmware")}</td>
								<td>${AdminDeviceView.text(row, "last_seen_at")}</td>
								<td>${AdminDeviceView.text(row, "last_punch_at")}</td>
								<td>@template.admin.statusBadge(status = AdminDeviceView.isActive(row) ? "active" : "inactive", t = t)</td>
								<td><a class="btn btn-outline btn-sm" href="/admin/devices?device=${AdminDeviceView.id(row, "id")}">${t.apply("device_open")}</a></td>
							</tr>
						@endfor
						@if(deviceResult.isEmpty())
							<tr><td colspan="10" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
			</div>
			@template.admin.pager(basePath = "/admin/devices", result = deviceResult,
					carry = carry, t = t, pageParam = "dev_page")

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("device_agents")} (${agentResult.total()})</h2>
				</div>
				<p class="text-muted">${t.apply("device_agents_hint")}</p>
				<form method="POST">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="agent_issue">
					<div class="org-form-grid">
						@if(filters.companyId() > 0)
							<input type="hidden" name="company_id" value="${filters.companyId()}">
						@else
							<div class="form-row">
								<label for="agent_company">${t.apply("company")}</label>
								<select id="agent_company" name="company_id" required>
									<option value="">—</option>
									@for(ActiveCompanies.CompanyOption option : companyOptions)
										<option value="${option.id()}">${option.name()}</option>
									@endfor
								</select>
							</div>
						@endif
						<div class="form-row">
							<label for="agent_name">${t.apply("device_agent_name")}</label>
							<input type="text" id="agent_name" name="name" required maxlength="100">
						</div>
					</div>
					<div class="form-footer">
						@if(actionsEnabled)
							<button type="submit" class="btn btn-blue">${t.apply("device_agent_issue")}</button>
						@endif
					</div>
				</form>
				<div class="table-wrap">
				<table class="tbl">
					<thead>
						<tr>
							<th>${t.apply("device_agent_name")}</th>
							<th>${t.apply("company")}</th>
							<th>${t.apply("device_agent_token")}</th>
							<th>${t.apply("device_agent_version")}</th>
							<th>${t.apply("device_last_seen")}</th>
							<th>${t.apply("device_ip")}</th>
							<th>${t.apply("device_agent_report")}</th>
							<th>${t.apply("status")}</th>
							<th></th>
						</tr>
					</thead>
					<tbody>
						@for(DeviceAgent agent : agentResult.data())
							<tr>
								<td class="bold">${agent.name()}</td>
								<td>${agent.companyId()}</td>
								<td class="text-muted">…${agent.tokenHint()}</td>
								<td>${AdminDeviceView.text(agent.agentVersion())}</td>
								<td>${AdminDeviceView.text(agent.lastSeenAt())}</td>
								<td class="text-muted">${AdminDeviceView.text(agent.lastSeenIp())}</td>
								<td class="text-muted">
									@for(String line : AdminDeviceView.reportLines(agent.lastReport()))
										<div>${line}</div>
									@endfor
								</td>
								<td>@template.admin.statusBadge(status = agent.active() ? "active" : "inactive", t = t)</td>
								<td>
									@if(actionsEnabled)
										<form method="POST">
											<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
											<input type="hidden" name="action" value="agent_active">
											<input type="hidden" name="id" value="${agent.id()}">
											<input type="hidden" name="active" value="${agent.active() ? "0" : "1"}">
											<button type="submit" class="btn btn-sm ${agent.active() ? "btn-red" : "btn-blue"}">${t.apply(agent.active() ? "revoke" : "restore")}</button>
										</form>
									@endif
								</td>
							</tr>
						@endfor
						@if(agentResult.isEmpty())
							<tr><td colspan="9" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
			</div>
			@template.admin.pager(basePath = "/admin/devices", result = agentResult,
					carry = carry, t = t, pageParam = "agent_page")

			@template.admin.devicePunches(result = punchResult,
				title = t.apply("device_latest_punches"), t = t)
			@template.admin.pager(basePath = "/admin/devices", result = punchResult,
					carry = carry, t = t, pageParam = "punch_page")
		@else
			<div class="toolbar page-toolbar">
				<a href="/admin/devices" class="btn btn-gray">${t.apply("back")}</a>
				@if(liveSeconds > 0)
					<a href="/admin/devices?device=${selected.id()}" class="btn btn-gray">${t.apply("device_live_stop")}</a>
				@else
					<a href="/admin/devices?device=${selected.id()}&live=1" class="btn btn-outline">${t.apply("device_live_start")}</a>
				@endif
			</div>

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${selected.name()} — ${selected.serialNumber()}</h2>
					@if(actionsEnabled)
						<form method="POST">
							<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
							<input type="hidden" name="action" value="device_active">
							<input type="hidden" name="id" value="${selected.id()}">
							<input type="hidden" name="active" value="${selected.active() ? "0" : "1"}">
							<button type="submit" class="btn btn-sm ${selected.active() ? "btn-red" : "btn-blue"}">${t.apply(selected.active() ? "device_deactivate" : "device_activate")}</button>
						</form>
					@endif
				</div>
				<div class="table-wrap">
				<table class="tbl">
					<tbody>
						<tr><th>${t.apply("status")}</th><td>@template.admin.statusBadge(status = selected.active() ? "active" : "inactive", t = t)</td></tr>
						<tr><th>${t.apply("device_vendor")}</th><td>${selected.vendor()}</td></tr>
						<tr><th>${t.apply("company")}</th><td>${AdminDeviceView.text(selectedRow, "company_name")} (${selected.companyId()})</td></tr>
						<tr><th>${t.apply("branch")}</th><td>${AdminDeviceView.text(selectedRow, "branch_name")} (${selected.branchId()})</td></tr>
						<tr><th>${t.apply("device_model")}</th><td>${AdminDeviceView.text(selected.model())} / ${AdminDeviceView.text(selected.firmware())}</td></tr>
						<tr><th>${t.apply("device_push_version")}</th><td>${AdminDeviceView.text(selected.pushVersion())}</td></tr>
						<tr><th>${t.apply("device_time_zone")}</th><td>${selected.deviceTimeZone()}</td></tr>
						<tr><th>${t.apply("device_last_seen")}</th><td>${AdminDeviceView.text(selected.lastSeenAt())} (${AdminDeviceView.text(selected.lastSeenIp())})</td></tr>
						<tr><th>${t.apply("device_last_handshake")}</th><td>${AdminDeviceView.text(selected.lastHandshakeAt())}</td></tr>
					</tbody>
				</table>
				</div>
			</div>

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("device_punch_counts")}</h2>
				</div>
				<div class="table-wrap">
				<table class="tbl">
					<thead><tr><th>${t.apply("status")}</th><th>${t.apply("device_delivered_via")}</th><th class="col-center">${t.apply("device_punches")}</th></tr></thead>
					<tbody>
						@for(Map<String, Object> row : counts)
							<tr>
								<td>${AdminDeviceView.text(row, "processing_state")}</td>
								<td>${AdminDeviceView.text(row, "delivered_via")}</td>
								<td class="col-center">${AdminDeviceView.text(row, "punches")}</td>
							</tr>
						@endfor
						@if(counts.isEmpty())
							<tr><td colspan="3" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
			</div>

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("device_import_title")}</h2>
				</div>
				<p class="text-muted">${t.apply("device_import_hint")}</p>
				<form method="POST" enctype="multipart/form-data">
					<input type="hidden" name="${csrfParameterName}" value="${csrfToken}">
					<input type="hidden" name="action" value="import">
					<input type="hidden" name="id" value="${selected.id()}">
					<div class="form-row">
						<label for="attlog_file">attlog.dat</label>
						<input type="file" id="attlog_file" name="file" required>
					</div>
					<div class="form-footer">
						@if(actionsEnabled)
							<button type="submit" class="btn btn-blue">${t.apply("device_import")}</button>
						@endif
					</div>
				</form>
			</div>

			@template.admin.devicePunches(result = punchResult,
				title = t.apply("device_punches"), t = t)
			@template.admin.pager(basePath = "/admin/devices", result = punchResult,
					carry = carry, t = t, pageParam = "punch_page")

			<div class="data-table-card">
				<div class="data-table-head">
					<h2 class="data-table-title">${t.apply("device_malformed")} (${malformedResult.total()})</h2>
				</div>
				<div class="table-wrap">
				<table class="tbl">
					<thead><tr><th>${t.apply("device_received_at")}</th><th>${t.apply("device_raw_line")}</th></tr></thead>
					<tbody>
						@for(Map<String, Object> row : malformedResult.data())
							<tr><td>${AdminDeviceView.text(row, "received_at")}</td><td class="text-muted">${AdminDeviceView.text(row, "raw_line")}</td></tr>
						@endfor
						@if(malformedResult.isEmpty())
							<tr><td colspan="2" class="data-table-empty">${t.apply("no_data")}</td></tr>
						@endif
					</tbody>
				</table>
				</div>
			</div>
			@template.admin.pager(basePath = "/admin/devices", result = malformedResult,
					carry = carry, t = t, pageParam = "mal_page")
		@endif
	`)
