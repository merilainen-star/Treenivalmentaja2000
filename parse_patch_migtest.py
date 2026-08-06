import re

with open("app/src/androidTest/java/fi/merilainen/treenivalmentaja/data/local/MigrationTest.kt", "r") as f:
    content = f.read()

content = content.replace("MIGRATION_1_2", "MIGRATION_3_4")
content = content.replace("migrate1To2", "migrate3To4")
content = content.replace("helper.createDatabase(TEST_DB, 1)", "helper.createDatabase(TEST_DB, 3)")
content = content.replace("helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_3_4)", "helper.runMigrationsAndValidate(TEST_DB, 4, true, MIGRATION_3_4)")

with open("app/src/androidTest/java/fi/merilainen/treenivalmentaja/data/local/MigrationTest.kt", "w") as f:
    f.write(content)
