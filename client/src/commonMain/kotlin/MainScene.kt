import event.PacketEvent
import event.ResizedEvent
import korlibs.image.font.Font
import korlibs.image.text.TextAlignment
import korlibs.korge.scene.Scene
import korlibs.korge.style.*
import korlibs.korge.view.*
import network.ServerClosedPacket
import newui.mainView
import ui.createRoomMenu
import ui.loadingMenu
import ui.loginMenuView
import ui.newCreateRoomMenu
import util.ColorPalette
import util.launchNow
import util.transform

val styler: ViewStyles.() -> Unit = {
    textFont = font
    textAlignment = TextAlignment.MIDDLE_CENTER
    textSize = 100f
    textColor = ColorPalette.text
}

class MainScene : Scene() {
    override suspend fun SContainer.sceneMain() {
        screen = fixedSizeContainer(size)
        onStageResized { width, height ->
            screen.size(width, height)
            dispatch(ResizedEvent())
        }
        screen.onEvent(PacketEvent) {
            val packet = it.packet
            if (packet !is ServerClosedPacket) return@onEvent
            screen.removeChildren()
            screen.loadingMenu("서버와의 연결이 끊겼습니다") {
                launchNow { scene.changeTo<MainScene>() }
            }
        }

        screen.container {
            text(version, textSize = 30f) {
            }.zIndex(100)
            zIndex(100)
        }.transform {
            val padding = 10
            positionY(screen.height - height - padding)
            positionX(padding*2)
        }
        mainView()
//        newCreateRoomMenu()
//        loginMenuView()
    }
}