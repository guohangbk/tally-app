package io.github.guohangbk.tally.domain

/**
 * 可插拔排序值：新分类落在同层末尾（`last + STEP`），重排只交换受影响区间的槽位值，
 * 不重写全表；只有同层出现重复值时才对那一层重新编号。
 */
object SortOrder {
    const val STEP = 100.0

    fun afterLast(last: Double?): Double = last?.plus(STEP) ?: STEP

    /**
     * 把 [ordered] 中的 [movedId] 移到 [targetIndex]，返回需要写回的排序值。
     * 受影响区间内的成员各取区间原有的一个槽位值，因此只改这几个，不动全表。
     */
    fun reorder(ordered: List<Pair<String, Double>>, movedId: String, targetIndex: Int): Map<String, Double> {
        val index = ordered.indexOfFirst { it.first == movedId }
        if (index < 0) return emptyMap()
        val clamped = targetIndex.coerceIn(0, ordered.lastIndex)
        if (clamped == index) return emptyMap()
        val low = minOf(index, clamped)
        val high = maxOf(index, clamped)
        val slotValues = ordered.subList(low, high + 1).map { it.second }
        val reordered = ordered.toMutableList().apply { add(clamped, removeAt(index)) }
        val updates = LinkedHashMap<String, Double>()
        reordered.subList(low, high + 1).forEachIndexed { offset, entry ->
            val value = slotValues[offset]
            if (entry.second != value) updates[entry.first] = value
        }
        return updates
    }

    /** 同层值重复或无序时重新编号，仅作用于该层。 */
    fun needsRenumber(ordered: List<Pair<String, Double>>): Boolean =
        ordered.zipWithNext().any { (before, after) -> after.second <= before.second }

    fun renumber(size: Int): List<Double> = (1..size).map { it * STEP }
}
