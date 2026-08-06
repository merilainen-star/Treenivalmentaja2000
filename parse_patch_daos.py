import re

with open("app/src/main/java/fi/merilainen/treenivalmentaja/data/local/dao/Daos.kt", "r") as f:
    content = f.read()

content = content.replace(
"""  @Query("SELECT * FROM workout_sessions WHERE id = :id")
  @Query("SELECT * FROM workout_sessions WHERE status = :status")
  suspend fun getByStatus(status: SessionStatus): List<WorkoutSessionEntity>

  suspend fun getById(id: String): WorkoutSessionEntity?""",
"""  @Query("SELECT * FROM workout_sessions WHERE id = :id")
  suspend fun getById(id: String): WorkoutSessionEntity?

  @Query("SELECT * FROM workout_sessions WHERE status = :status")
  suspend fun getByStatus(status: SessionStatus): List<WorkoutSessionEntity>""")

with open("app/src/main/java/fi/merilainen/treenivalmentaja/data/local/dao/Daos.kt", "w") as f:
    f.write(content)
