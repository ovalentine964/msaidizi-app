package com.msaidizi.app.core.database

import androidx.room.*
import com.msaidizi.app.core.model.RichHabitEntry
import com.msaidizi.app.core.model.MindsetLessonEntity

/**
 * DAO for Rich Habits tracking.
 */
@Dao
interface RichHabitsDao {

    @Query("SELECT * FROM rich_habits WHERE date = :date")
    suspend fun getEntriesForDate(date: String): List<RichHabitEntry>

    @Query("SELECT * FROM rich_habits WHERE date = :date AND habitId = :habitId LIMIT 1")
    suspend fun getEntry(date: String, habitId: String): RichHabitEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RichHabitEntry)

    @Query("SELECT COUNT(*) FROM rich_habits WHERE date = :date AND completed = 1")
    suspend fun getCompletedCountForDate(date: String): Int

    @Query("SELECT AVG(CAST(completed_count AS REAL)) FROM (SELECT date, COUNT(*) as completed_count FROM rich_habits WHERE completed = 1 AND date >= :sinceDate GROUP BY date)")
    suspend fun getAverageCompletedSince(sinceDate: String): Double?

    @Query("SELECT DISTINCT date FROM rich_habits WHERE completed = 1 ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentCompletedDates(limit: Int): List<String>
}

/**
 * DAO for Mindset Academy lessons.
 */
@Dao
interface MindsetLessonDao {

    @Query("SELECT * FROM mindset_lessons WHERE delivered = 0 ORDER BY sortOrder ASC LIMIT 1")
    suspend fun getNextUndeliveredLesson(): MindsetLessonEntity?

    @Query("SELECT * FROM mindset_lessons WHERE category = :category AND completed = 0 ORDER BY sortOrder ASC LIMIT 1")
    suspend fun getNextIncompleteByCategory(category: String): MindsetLessonEntity?

    @Query("SELECT * FROM mindset_lessons WHERE lessonId = :lessonId")
    suspend fun getLesson(lessonId: String): MindsetLessonEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(lesson: MindsetLessonEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(lessons: List<MindsetLessonEntity>)

    @Query("UPDATE mindset_lessons SET delivered = 1, deliveredAt = :timestamp WHERE lessonId = :lessonId")
    suspend fun markDelivered(lessonId: String, timestamp: Long = System.currentTimeMillis() / 1000)

    @Query("UPDATE mindset_lessons SET completed = 1, completedAt = :timestamp WHERE lessonId = :lessonId")
    suspend fun markCompleted(lessonId: String, timestamp: Long = System.currentTimeMillis() / 1000)

    @Query("SELECT COUNT(*) FROM mindset_lessons WHERE completed = 1")
    suspend fun getCompletedCount(): Int

    @Query("SELECT COUNT(*) FROM mindset_lessons")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM mindset_lessons WHERE category = :category AND completed = 1")
    suspend fun getCompletedCountByCategory(category: String): Int

    @Query("SELECT COUNT(*) FROM mindset_lessons WHERE category = :category")
    suspend fun getTotalCountByCategory(category: String): Int
}
