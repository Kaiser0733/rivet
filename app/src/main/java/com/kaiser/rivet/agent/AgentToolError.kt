package com.kaiser.rivet.agent

import com.kaiser.rivet.runtime.TermuxCommand
import com.kaiser.rivet.workspace.WorkspaceFailure
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object AgentToolError {
    // Every WorkspaceFailure.Reason already carries a complete explanation. Derive
    // the entries from the reason itself so a new reason cannot reach the model as a
    // bare code, which is what made the model retry the same edit until the runaway
    // guard fired.
    private val workspaceActions = WorkspaceFailure.Reason.entries.associate {
        it.name.lowercase() to it.message
    }
    private val actions = workspaceActions + mapOf(
        "sync_required" to "Rivet could not safely reconcile project changes. Stop and review the project before retrying.",
        "workspace_unavailable" to "Choose the project again before retrying the command.",
        "workspace_changed" to "The selected project changed. Start a new request in the current project.",
        "runtime_unavailable" to "Rivet could not start the command runner. Try again.",
        "denied" to "The user denied this mutation. Do not retry it without a new request.",
        "checkpoint_unavailable" to "Rivet's Undo covers up to 50,000 items and 500 MB per project. If this project is larger, add the biggest generated folder to .rivetignore and retry.",
        "checkpoint_limit" to "Rivet's Undo covers up to 50,000 items and 500 MB per project. Add the biggest generated folder to .rivetignore and retry.",
        "interrupted" to "Inspect workspace state before retrying; the operation outcome is unknown.",
        "outcome_unknown" to "The earlier execution may have changed state. Rivet withheld this replay. Verify the effect and ask the user before a new action.",
        "already_attempted" to "This action already ran and returned an error. Verify its effects before asking the user for a new action.",
        "journal_unavailable" to "Rivet could not save a receipt for this action. The action was withheld.",
        "worker_shell_scope_unavailable" to "Rivet cannot enforce this worker’s write scope for shell commands. Use scoped file tools or ask the coordinator to run the command.",
        "unsafe_entry" to "Rivet found a project item it could not safely access. Remove or rename it before retrying.",
        "unsupported_system_management" to "Rivet cannot install apps, packages, or other Android system software.",
        "secret" to "Secrets never go into project memory. Remove the credential and record only a durable, committable fact.",
        "garden_first" to "Project memory is at its hard cap. Garden it first: merge related facts and delete stale lines.",
        "empty" to "The memory fact was empty. Write one durable sentence.",
        "too_long" to "The memory fact is too long. Keep one fact under 500 characters.",
        "reserved_memory" to "Project memory belongs to the user. Use the remember tool; file tools cannot edit .rivet/memory.md directly.",
        "approval_unavailable" to "Rivet could not safely request confirmation for this operation, so it stopped.",
        "approval_expired" to "The approval expired. Rivet withheld the action.",
        "approval_changed" to "The target or workspace changed while approval was pending. Rivet withheld the action.",
        "process_limit" to "Rivet already has the maximum number of active project operations. Stop one in Tools and retry.",
        "output_unavailable" to "Cached command output expired or does not exist. Re-run the command with grep, tail, or max_lines.",
        "thermal_blocked" to "The phone is too hot. Wait for it to cool before retrying, and do not retry the same long command now.",
        "battery_low" to "Battery is low — terminal paused for long jobs. Charge the phone or run a short command.",
        "foreground_service_unavailable" to "Rivet couldn't keep the background process or local preview running. Try again while Rivet is open.",
        "preview_unavailable" to "Rivet couldn't start a local preview for this project. Check the selected files and try again.",
        "process_unavailable" to "That Rivet process is no longer active. List active processes before trying again.",
        "invalid_url" to "Rivet only downloads from valid HTTPS addresses without embedded credentials.",
        "redirect_blocked" to "Rivet stopped the download because the redirect was not allowed.",
        "download_too_large" to "This download exceeds Rivet's 64 MiB limit. Choose a smaller file.",
        "download_sha_mismatch" to "The downloaded file did not match the expected checksum. No project file was changed.",
        "destination_exists" to "That project file already exists. Read it first and provide its current hash before replacing it.",
        "download_failed" to "The server could not provide this file. Check the address and try again.",
        "network_error" to "Rivet couldn't reach the download server. Check the address and connection, then try again.",
        "invalid_checksum" to "Use a 64-character SHA-256 checksum or leave the checksum blank.",
        "capability_addon_required" to "That capability needs the separate phone add-on. See Settings → Phone access → Advanced.",
        "app_not_found" to "Android couldn't open that app. Check the package name or choose an installed app.",
        "no_app_for_file" to "Android couldn't open this file. Check the path and install an app that can open its type.",
        "share_unavailable" to "Android couldn't show the share sheet. Check the file or try sharing text from Rivet.",
        "compose_unavailable" to "Android couldn't open a draft. Choose or install an email, messaging, or calendar app.",
        "invalid_arguments" to "The tool arguments did not match this tool's JSON contract. Re-read the argument names and send one complete JSON object.",
        "phone_shot_denied" to "Screen capture was declined.",
        "phone_shot_cancelled" to "Screen capture was cancelled.",
        "phone_shot_timeout" to "Screen capture timed out.",
        "phone_shot_notification_missing" to "Android didn't post Rivet's capture notification. Check Screen capture notifications.",
        "phone_shot_save_failed" to "Rivet captured the screen but couldn't save the image to the project.",
        "phone_shot_needs_project" to "Choose a project folder before capturing the screen.",
        "phone_shot_unavailable" to "Rivet couldn't start screen capture on this device.",
        "phone_shot_needs_foreground" to "Rivet needs to be on screen to ask for capture permission — bring Rivet to the front and retry",
        "phone_shot_notifications_off" to "Turn on Rivet's Screen capture notifications, then try again.",
        "phone_shot_busy" to "Finish or cancel the current screen capture, then try again.",
        "termux_missing" to "Install the Termux app, then follow the setup in Settings → Phone access.",
        "termux_external_apps_off" to "Termux reported that external commands are disabled. " + TermuxCommand.SETUP,
        "termux_no_result" to "Termux sent a reply without readable command results. The command may already have run.",
        "termux_permission_required" to TermuxCommand.SETUP,
        "termux_cwd_unavailable" to "Termux can't use that working directory. Choose a shared-storage folder or pass an absolute Termux directory.",
        "termux_unavailable" to "Rivet couldn't start the Termux service. Open Termux once, then try again while Rivet is open.",
        "termux_operation_unavailable" to "This Termux operation ID is unavailable in this conversation.",
        "termux_output_missing" to "Check workspace access and read the saved Termux output at output_path.",
        "termux_command_failed" to "Termux reported an error. Check the returned details before retrying.",
        "grant_missing" to "Choose a folder in Settings → Phone access, then use its exact grants/ path.",
        "grant_revoked" to "Grant revoked — re-grant in Settings → Phone access.",
        "grant_read_only" to "This grant is read-only. Choose a folder that allows write access in Settings → Phone access.",
        "inbox_read_only" to "The inbox is read-only. Create a separate copy if you need to edit the contents.",
        "inbox_search_unavailable" to "Use list_directory and read_file to inspect the inbox.",
        "cross_folder_move" to "Move files within the same project or granted folder.",
        "file_project_unavailable" to "Choose a project or use a grants/ or inbox/ path.",
        "invalid_offset" to "Read the file from a different offset. This one is past the end or splits a character.",
        "output_limit" to "Ask for a smaller part of this file. The requested range is too large for one result.",
        "outside_file_scope" to "That path is outside this worker's write_scope. Only edit paths declared in your write_scope.",
        "worker_read_only" to "This subagent is read-only: no write scope was declared, so Rivet refused the write without asking.",
        "owned_by_worker" to "Await that worker or ask the user to stop it before editing its files.",
        "browser_busy" to "Another agent in this run is already driving the browser. Wait for it to finish or use other tools.",
        "page_modal_blocked" to "Call browser_reset(reopen=true) to clear the blocking JavaScript dialog, then retry.",
        "browser_eval_timeout" to "The JS expression timed out. Simplify it or call browser_reset(reopen=true) if the page is hung.",
        "browser_eval_exception" to "Fix the JS expression or inspect page errors with browser_console.",
        "browser_origin_mismatch" to "Open the expected same-origin page with browser_open before evaluating.",
        "browser_js_disabled" to "Enable JavaScript in browser settings or use browser_read instead.",
        "browser_sensitive_field" to "Use the page in your browser or take over and type the password/OTP/card yourself.",
        "browser_wait_timeout" to "Inspect current state with browser_status, browser_console, or browser_read before retrying.",
        "browser_renderer_unavailable" to "Call browser_reset(reopen=true) to rebuild the renderer, then retry.",
        "browser_target" to "Check the selector or use browser_read refs before retrying.",
        "browser_renderer_blocked" to "Close other apps or call browser_reset(reopen=true); if memory is low, use browser_read instead.",
        "browser_cleartext_blocked" to "Use https:// for public pages, or pass preview_root/preview_entry (or http://127.0.0.1:PORT/) for local files.",
        "browser_loopback_unreachable" to "Start the preview with preview_root/preview_entry or start_preview, or start a server with spawn_process.",
        "browser_load_failed" to "Check the URL, network, or server process, then retry browser_open.",
        "browser_not_open" to "Open a page with browser_open before using browser tools.",
        "browser_handoff" to "Continue sign-in in the phone's browser, then share the page back.",
        "browser_empty" to "Wait for the page to finish loading, then retry browser_read.",
        "browser_chunk_out_of_range" to "Read chunk 0 first, then use a returned chunk index.",
        "browser_page_loading" to "Wait for the page to finish loading, then retry browser_read.",
        "browser_screenshot_blocked" to "Use browser_read/browser_find in this memory tier, or open the page in the phone's browser.",
        "browser_teardown_pending" to "Wait for the browser to finish closing, then retry.",
        "browser_timeout" to "Check the server/process and retry browser_open, or call browser_reset(reopen=true).",
        "browser_network" to "Check the URL, server process, and connection, then retry.",
        "browser_http" to "Check preview_entry/server routes or retry browser_open; a 4xx/5xx page may still be readable.",
        "browser_too_large" to "Use a smaller page or open it in the phone's browser.",
        "browser_redirect" to "Use the final page URL or check the server redirects.",
    )
    fun content(code: String, statusCode: Int? = null, subject: String? = null): String {
        val baseCode = if (code.startsWith("owned_by_worker:")) "owned_by_worker" else code
        val workerJob = if (code.startsWith("owned_by_worker:")) {
            code.removePrefix("owned_by_worker:").takeIf { it.isNotEmpty() }
        } else null
        return buildJsonObject {
            put("error", baseCode)
            workerJob?.let { put("worker", it) }
            actions[baseCode]?.let {
                put("retryable", false)
                put("required_action", it)
            }
            statusCode?.let { put("status_code", it) }
            // The path that blocked the call, when the failure has one.
            subject?.let { put("path", it) }
        }.toString()
    }

    fun action(code: String): String? =
        actions[if (code.startsWith("owned_by_worker:")) "owned_by_worker" else code]
}
