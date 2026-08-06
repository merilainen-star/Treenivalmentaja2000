with open("app/src/main/java/fi/merilainen/treenivalmentaja/WorkoutViewModel.kt", "r") as f:
    lines = f.readlines()

# delete extra } at 217
del lines[216]

with open("app/src/main/java/fi/merilainen/treenivalmentaja/WorkoutViewModel.kt", "w") as f:
    f.writelines(lines)
