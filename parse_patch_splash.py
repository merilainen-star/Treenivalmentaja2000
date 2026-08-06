import re

with open("app/src/main/java/fi/merilainen/treenivalmentaja/SplashScreen.kt", "r") as f:
    content = f.read()

# Replace Box background with #1D262F
bg_replacement = """        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1D262F)),
        contentAlignment = Alignment.Center"""
content = re.sub(r'        modifier = Modifier\s+\.fillMaxSize\(\)\s+\.background\([\s\S]*?\),\s+contentAlignment = Alignment\.Center', bg_replacement, content)

# Replace Icon with Image showing splash_logo
icon_replacement = """            Image(
                painter = painterResource(id = R.drawable.splash_logo),
                contentDescription = "App Icon",
                modifier = Modifier.size(160.dp),
                contentScale = ContentScale.Fit
            )"""
content = re.sub(r'            Icon\([\s\S]*?modifier = Modifier\.size\(120\.dp\)\n            \)', icon_replacement, content)

with open("app/src/main/java/fi/merilainen/treenivalmentaja/SplashScreen.kt", "w") as f:
    f.write(content)
