package com.inkyandroid.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inkyandroid.R
import com.inkyandroid.core.engine.InkIssue
import com.inkyandroid.core.engine.InkIssues
import com.inkyandroid.core.engine.IssueCounts
import com.inkyandroid.core.engine.IssueKind
import com.inkyandroid.ui.InkIcons
import desktopkit.components.DeskIcons
import desktopkit.components.HorizontalRule
import desktopkit.components.animatedStateBackground
import desktopkit.components.collectState
import desktopkit.components.deskClickable
import desktopkit.components.focusRing
import desktopkit.components.verticalScrollbar
import desktopkit.screens.MessageScreen
import desktopkit.theme.DeskTheme

/** The issues of one file, in desktop Inky's order: by seriousness, then by line. A null [path] holds those that name no file. */
data class IssueGroup(val path: String?, val issues: List<InkIssue>)

/**
 * [issues] grouped by the file they are in. The groups follow [fileOrder] (the project's own order:
 * the main file, its includes, the rest); files the project does not list come after them by name,
 * and what names no file at all comes last.
 */
fun groupIssues(issues: List<InkIssue>, fileOrder: List<String>): List<IssueGroup> {
    val byFile = InkIssues.sorted(issues).groupBy { it.file }
    val known = fileOrder.filter { it in byFile }
    val others = byFile.keys.filterNotNull().filter { it !in fileOrder }.sorted()
    return (known + others).map { IssueGroup(it, byFile.getValue(it)) } + listOfNotNull(byFile[null]?.let { IssueGroup(null, it) })
}

/**
 * The counts shown beside the issue marks. Desktop Inky counts a runtime warning nowhere (its summary
 * says "No issues." beside a list that has one); here it counts as the warning it is.
 */
fun countIssues(issues: List<InkIssue>): IssueCounts = IssueCounts(
    errors = issues.count { it.isError },
    warnings = issues.count { it.kind == IssueKind.Warning || it.kind == IssueKind.RuntimeWarning },
    todos = issues.count { it.kind == IssueKind.Todo },
)

/**
 * Every problem of the project: the compiler's errors and warnings, the author's TODO notes and
 * what the running story raised, grouped by file. Activating one shows its line in the editor.
 *
 * @param fileOrder the project's files in its own order, for the order of the groups.
 */
@Composable
fun IssuesPane(
    issues: List<InkIssue>,
    fileOrder: List<String>,
    onOpen: (InkIssue) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    showSummary: Boolean = true,
) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    if (issues.isEmpty()) {
        Box(modifier.fillMaxSize().padding(contentPadding)) {
            MessageScreen(
                title = stringResource(R.string.issues_none_title),
                icon = DeskIcons.CheckCircle,
                description = stringResource(R.string.issues_none_description),
            )
        }
        return
    }
    val groups = remember(issues, fileOrder) { groupIssues(issues, fileOrder) }
    val headings = groups.size > 1 || groups.firstOrNull()?.path == null || fileOrder.size > 1
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().background(colors.background).verticalScrollbar(listState),
        contentPadding = contentPadding,
    ) {
        if (showSummary) {
            item(key = "summary") {
                IssueSummary(countIssues(issues), Modifier.padding(horizontal = dimens.pagePadding, vertical = dimens.gapM))
                HorizontalRule()
            }
        }
        groups.forEach { group ->
            if (headings) {
                item(key = "group:" + group.path) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = dimens.pagePadding, end = dimens.pagePadding, top = dimens.gapL, bottom = dimens.gapXs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            group.path ?: stringResource(R.string.issues_project_wide),
                            style = DeskTheme.type.labelSmall,
                            color = colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        Text(
                            pluralStringResource(R.plurals.issues_count, group.issues.size, group.issues.size),
                            style = DeskTheme.type.caption,
                            color = colors.textTertiary,
                        )
                    }
                }
            }
            items(group.issues.size, key = { index -> "issue:" + group.path + ":" + index }) { index ->
                IssueRow(group.issues[index]) { onOpen(group.issues[index]) }
            }
        }
    }
}

/** "2 errors · 1 warning · 3 TODOs": the counts desktop Inky keeps in its toolbar, each with its mark. */
@Composable
fun IssueSummary(counts: IssueCounts, modifier: Modifier = Modifier, compact: Boolean = false) {
    val dimens = DeskTheme.dimens
    // compact (a narrow pane): the marks with their numbers only; the whole summary keeps its words for TalkBack
    fun text(count: Int, words: String) = if (compact) count.toString() else words
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (compact) dimens.gapS else dimens.gapL), verticalAlignment = Alignment.CenterVertically) {
        if (counts.errors > 0) SummaryPart(IssueKind.Error, text(counts.errors, pluralStringResource(R.plurals.issues_errors, counts.errors, counts.errors)))
        if (counts.warnings > 0) SummaryPart(IssueKind.Warning, text(counts.warnings, pluralStringResource(R.plurals.issues_warnings, counts.warnings, counts.warnings)))
        if (counts.todos > 0) SummaryPart(IssueKind.Todo, text(counts.todos, pluralStringResource(R.plurals.issues_todos, counts.todos, counts.todos)))
        if (counts.isEmpty) Text(stringResource(R.string.issues_none_title), style = DeskTheme.type.label, color = DeskTheme.colors.textSecondary)
    }
}

@Composable
private fun SummaryPart(kind: IssueKind, text: String) {
    val dimens = DeskTheme.dimens
    Row(horizontalArrangement = Arrangement.spacedBy(dimens.gapXs), verticalAlignment = Alignment.CenterVertically) {
        Icon(issueIcon(kind), contentDescription = null, tint = issueTone(kind), modifier = Modifier.size(dimens.icon))
        Text(text, style = DeskTheme.type.label, color = DeskTheme.colors.text, maxLines = 1)
    }
}

/** One issue: its mark, what it says, and the line it is on. The whole row leads there. */
@Composable
private fun IssueRow(issue: InkIssue, onClick: () -> Unit) {
    val colors = DeskTheme.colors
    val dimens = DeskTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val visual = interaction.collectState()
    val shape = RoundedCornerShape(dimens.radiusSmall)
    val kind = issueKindLabel(issue.kind)
    val line = issue.line?.takeIf { it > 0 }
    val place = if (line != null) stringResource(R.string.issue_line, line) else null
    val described = listOfNotNull(kind, place, issue.message).joinToString(", ")
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowHeight)
                .focusRing(visual, shape)
                .background(animatedStateBackground(visual).value)
                .deskClickable(interaction, onClick, role = Role.Button)
                .padding(horizontal = dimens.pagePadding, vertical = dimens.gapS)
                .semantics(mergeDescendants = true) { contentDescription = described },
            horizontalArrangement = Arrangement.spacedBy(dimens.gapM),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(issueIcon(issue.kind), contentDescription = null, tint = issueTone(issue.kind), modifier = Modifier.size(dimens.icon))
            Text(
                issue.message,
                style = DeskTheme.type.body,
                color = colors.text,
                maxLines = MESSAGE_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (place != null) Text(place, style = DeskTheme.type.mono, color = colors.textTertiary, maxLines = 1)
        }
        HorizontalRule(Modifier.padding(start = dimens.pagePadding))
    }
}

/** The mark of an issue: the kit's error and warning signs, a ticked note for a TODO. */
fun issueIcon(kind: IssueKind): ImageVector = when (kind) {
    IssueKind.Error, IssueKind.RuntimeError -> DeskIcons.Error
    IssueKind.Warning, IssueKind.RuntimeWarning -> DeskIcons.Warning
    IssueKind.Todo -> InkIcons.Todo
}

/** The colour of an issue's mark: the theme's danger and warning, and a quiet tone for a note to oneself. */
@Composable
fun issueTone(kind: IssueKind): Color = when (kind) {
    IssueKind.Error, IssueKind.RuntimeError -> DeskTheme.colors.danger
    IssueKind.Warning, IssueKind.RuntimeWarning -> DeskTheme.colors.warning
    IssueKind.Todo -> DeskTheme.colors.accentText
}

@Composable
private fun issueKindLabel(kind: IssueKind): String = stringResource(
    when (kind) {
        IssueKind.Error -> R.string.issue_kind_error
        IssueKind.RuntimeError -> R.string.issue_kind_runtime_error
        IssueKind.Warning -> R.string.issue_kind_warning
        IssueKind.RuntimeWarning -> R.string.issue_kind_runtime_warning
        IssueKind.Todo -> R.string.issue_kind_todo
    },
)

/** A compiler's message can be long; the list shows this much of it, the editor's marker all of it. */
private const val MESSAGE_LINES = 4
