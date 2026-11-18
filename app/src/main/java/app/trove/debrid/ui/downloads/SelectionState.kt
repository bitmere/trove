package app.trove.debrid.ui.downloads

class SelectionState(selectedIds: Set<String> = emptySet()) {
    val selectedIds: Set<String> = selectedIds.toSet()
    val count: Int get() = selectedIds.size
    val isEmpty: Boolean get() = selectedIds.isEmpty()
    val isSelecting: Boolean get() = !isEmpty

    fun isSelected(id: String): Boolean = id in selectedIds

    fun toggle(id: String): SelectionState =
        SelectionState(if (isSelected(id)) selectedIds - id else selectedIds + id)

    /** Replaces the selection with the supplied IDs. */
    fun selectAll(ids: Iterable<String>): SelectionState = SelectionState(ids.toSet())

    fun clear(): SelectionState = SelectionState()
}
