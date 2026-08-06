import re

with open("app/src/main/java/fi/merilainen/treenivalmentaja/SplashScreen.kt", "r") as f:
    content = f.read()

# Make the Image fillMaxSize and crop
image_replacement = """            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = "App Background and Logo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )"""
content = re.sub(r'            Image\([\s\S]*?contentScale = ContentScale\.Fit\n            \)', image_replacement, content)

with open("app/src/main/java/fi/merilainen/treenivalmentaja/SplashScreen.kt", "w") as f:
    f.write(content)
