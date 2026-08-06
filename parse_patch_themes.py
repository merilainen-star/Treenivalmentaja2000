import re
with open("app/src/main/res/values/themes.xml", "r") as f:
    content = f.read()

content = re.sub(r'<item name="windowSplashScreenAnimatedIcon">.*?</item>', '<item name="windowSplashScreenAnimatedIcon">@drawable/splash_icon_layer</item>', content)
with open("app/src/main/res/values/themes.xml", "w") as f:
    f.write(content)
