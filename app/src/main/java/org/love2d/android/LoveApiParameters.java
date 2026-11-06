/*
 * CodeLove - assinaturas de parametros da API LÖVE.
 *
 * Referencia de ajuda contextual do editor. As assinaturas acompanham a API
 * estavel do LÖVE 11.x e APIs conhecidas do ciclo 12.0 quando aplicavel.
 * A lista nao altera nem substitui o runtime do LÖVE.
 */
package org.love2d.android;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class LoveApiParameters {
    private static final Map<String, String> SIGNATURES = new HashMap<>();

    static {
        // Callbacks principais e eventos.
        add("love.load", "([args,] isReplay?)");
        add("love.update", "(dt)");
        add("love.draw", "()");
        add("love.quit", "()");
        add("love.run", "()");
        add("love.errhand", "(message)");
        add("love.errorhandler", "(message)");
        add("love.focus", "(focused)");
        add("love.visible", "(visible)");
        add("love.resize", "(width, height)");
        add("love.lowmemory", "()");
        add("love.threaderror", "(thread, error)");
        add("love.keypressed", "(key, scancode, isrepeat)");
        add("love.keyreleased", "(key, scancode)");
        add("love.textinput", "(text)");
        add("love.textedited", "(text, start, length)");
        add("love.mousepressed", "(x, y, button, istouch, presses)");
        add("love.mousereleased", "(x, y, button, istouch, presses)");
        add("love.mousemoved", "(x, y, dx, dy, istouch)");
        add("love.wheelmoved", "(x, y)");
        add("love.touchpressed", "(id, x, y, dx, dy, pressure)");
        add("love.touchreleased", "(id, x, y, dx, dy, pressure)");
        add("love.touchmoved", "(id, x, y, dx, dy, pressure)");
        add("love.joystickpressed", "(joystick, button)");
        add("love.joystickreleased", "(joystick, button)");
        add("love.joystickaxis", "(joystick, axis, value)");
        add("love.joystickhat", "(joystick, hat, direction)");
        add("love.joystickadded", "(joystick)");
        add("love.joystickremoved", "(joystick)");
        add("love.gamepadpressed", "(joystick, button)");
        add("love.gamepadreleased", "(joystick, button)");
        add("love.gamepadaxis", "(joystick, axis, value)");
        add("love.filedropped", "(file)");
        add("love.directorydropped", "(path)");
        add("love.dropbegan", "(x, y)");
        add("love.dropmoved", "(x, y)");
        add("love.dropcompleted", "(files)");
        add("love.audiodisconnected", "(device)");
        add("love.sensorupdated", "(sensor, ...)");
        add("love.localechanged", "(locale)");
        add("love.themechanged", "(theme)");
        add("love.exposed", "()");
        add("love.occluded", "()");

        // Graphics: desenho, estado, transformacoes e objetos.
        add("love.graphics.clear", "(r?, g?, b?, a?, clearstencil?, cleardepth?)");
        add("love.graphics.present", "()");
        add("love.graphics.origin", "()");
        add("love.graphics.push", "(stack?)");
        add("love.graphics.pop", "()");
        add("love.graphics.translate", "(x, y)");
        add("love.graphics.rotate", "(angle)");
        add("love.graphics.scale", "(sx, sy?)");
        add("love.graphics.shear", "(kx, ky)");
        add("love.graphics.applyTransform", "(transform)");
        add("love.graphics.replaceTransform", "(transform)");
        add("love.graphics.setColor", "(r, g, b, a?)");
        add("love.graphics.getColor", "()");
        add("love.graphics.setBackgroundColor", "(r, g, b, a?)");
        add("love.graphics.getBackgroundColor", "()");
        add("love.graphics.setLineWidth", "(width)");
        add("love.graphics.setLineStyle", "(style)");
        add("love.graphics.setLineJoin", "(join)");
        add("love.graphics.setPointSize", "(size)");
        add("love.graphics.setPointStyle", "(style)");
        add("love.graphics.setBlendMode", "(mode, alphamode?)");
        add("love.graphics.getBlendMode", "()");
        add("love.graphics.setDefaultFilter", "(min, mag, anisotropy?)");
        add("love.graphics.getDefaultFilter", "()");
        add("love.graphics.setFont", "(font)");
        add("love.graphics.getFont", "()");
        add("love.graphics.print", "(text, x, y, angle?, sx?, sy?, ox?, oy?, kx?, ky?)");
        add("love.graphics.printf", "(text, x, y, wraplimit, align?, angle?, sx?, sy?, ox?, oy?, kx?, ky?)");
        add("love.graphics.draw", "(drawable, x?, y?, angle?, sx?, sy?, ox?, oy?, kx?, ky?)");
        add("love.graphics.drawInstanced", "(mesh, instancecount, ...)");
        add("love.graphics.drawLayer", "(drawable, layer, ...)");
        add("love.graphics.points", "(x, y, ...)");
        add("love.graphics.line", "(x1, y1, x2, y2, ...)");
        add("love.graphics.rectangle", "(mode, x, y, width, height, rx?, ry?, segments?)");
        add("love.graphics.circle", "(mode, x, y, radius, segments?)");
        add("love.graphics.ellipse", "(mode, x, y, radiusx, radiusy, segments?)");
        add("love.graphics.arc", "(mode, x, y, radius, angle1, angle2, segments?)");
        add("love.graphics.polygon", "(mode, vertices)");
        add("love.graphics.newImage", "(filename, settings?)");
        add("love.graphics.newCanvas", "(width?, height?, settings?)");
        add("love.graphics.newQuad", "(x, y, width, height, sw, sh)");
        add("love.graphics.newFont", "(filename?, size?)");
        add("love.graphics.newText", "(font, text?)");
        add("love.graphics.newMesh", "(vertices, mode?, usage?)");
        add("love.graphics.newShader", "(code)");
        add("love.graphics.newSpriteBatch", "(texture, size?, usage?)");
        add("love.graphics.newParticleSystem", "(texture, buffer?)");
        add("love.graphics.newVideo", "(filename)");
        add("love.graphics.setCanvas", "(canvas, ...)");
        add("love.graphics.getCanvas", "()");
        add("love.graphics.setScissor", "(x?, y?, width?, height?)");
        add("love.graphics.getScissor", "()");
        add("love.graphics.setStencilTest", "(comparemode?, value?)");
        add("love.graphics.setShader", "(shader?)");
        add("love.graphics.getShader", "()");
        add("love.graphics.setWireframe", "(enable)");
        add("love.graphics.setColorMask", "(r, g, b, a)");
        add("love.graphics.getDimensions", "()");
        add("love.graphics.getWidth", "()");
        add("love.graphics.getHeight", "()");
        add("love.graphics.getDPIScale", "()");
        add("love.graphics.getStats", "()");
        add("love.graphics.flushBatch", "()");
        add("love.graphics.captureScreenshot", "(callback)");

        // Keyboard, mouse, touch, joystick e sensores.
        add("love.keyboard.isDown", "(key, ...)");
        add("love.keyboard.isScancodeDown", "(scancode, ...)");
        add("love.keyboard.setKeyRepeat", "(enable)");
        add("love.keyboard.setTextInput", "(enable, x?, y?, w?, h?)");
        add("love.keyboard.isModifierActive", "(key)");
        add("love.mouse.getPosition", "()");
        add("love.mouse.getX", "()");
        add("love.mouse.getY", "()");
        add("love.mouse.setPosition", "(x, y)");
        add("love.mouse.isDown", "(button, ...)");
        add("love.mouse.setVisible", "(visible)");
        add("love.mouse.setGrabbed", "(grabbed)");
        add("love.mouse.setRelativeMode", "(enable)");
        add("love.mouse.getGlobalPosition", "()");
        add("love.touch.getTouches", "()");
        add("love.touch.getPosition", "(id)");
        add("love.touch.getPressure", "(id)");
        add("love.joystick.getJoysticks", "()");
        add("love.joystick.getJoystickCount", "()");
        add("love.joystick.loadGamepadMappings", "(filename)");
        add("love.joystick.saveGamepadMappings", "(filename)");
        add("love.sensor.getSensorTypes", "()");
        add("love.sensor.isEnabled", "(sensor)");
        add("love.sensor.setEnabled", "(sensor, enabled)");
        add("love.sensor.getData", "(sensor)");

        // Audio.
        add("love.audio.newSource", "(filename, type?)");
        add("love.audio.play", "(source)");
        add("love.audio.pause", "(source?)");
        add("love.audio.stop", "(source?)");
        add("love.audio.setVolume", "(volume)");
        add("love.audio.getVolume", "()");
        add("love.audio.setDistanceModel", "(model)");
        add("love.audio.getActiveSources", "()");
        add("love.audio.getSourceCount", "()");
        add("love.audio.setEffect", "(name, settings?)");
        add("love.audio.getEffect", "(name)");
        add("love.audio.setActiveEffects", "(effects)");
        add("love.audio.getActiveEffects", "()");
        add("love.audio.setPlaybackDevice", "(name)");
        add("love.audio.getPlaybackDevice", "()");
        add("love.audio.getPlaybackDevices", "()");
        
        // Filesystem e arquivos.
        add("love.filesystem.read", "(name, size?)");
        add("love.filesystem.write", "(name, data, size?)");
        add("love.filesystem.append", "(name, data, size?)");
        add("love.filesystem.lines", "(name)");
        add("love.filesystem.load", "(name)");
        add("love.filesystem.getInfo", "(path, filtertype?)");
        add("love.filesystem.getDirectoryItems", "(path)");
        add("love.filesystem.createDirectory", "(name)");
        add("love.filesystem.remove", "(name)");
        add("love.filesystem.mount", "(archive, mountpoint?, appendToPath?)");
        add("love.filesystem.unmount", "(archive)");
        add("love.filesystem.mountFullPath", "(archive, mountpoint?, appendToPath?)");
        add("love.filesystem.unmountFullPath", "(archive)");
        add("love.filesystem.mountCommonPath", "(archive, mountpoint?, appendToPath?)");
        add("love.filesystem.unmountCommonPath", "(archive)");
        add("love.filesystem.getSaveDirectory", "()");
        add("love.filesystem.getSource", "()");
        add("love.filesystem.getSourceBaseDirectory", "()");
        add("love.filesystem.getWorkingDirectory", "()");
        add("love.filesystem.setIdentity", "(identity, appendToPath?)");
        add("love.filesystem.getIdentity", "()");
        add("love.filesystem.openFile", "(name, mode?)");
        add("love.filesystem.openNativeFile", "(name, mode?)");
        add("love.filesystem.getRealDirectory", "( filepath )");

        // Janela, sistema, temporizador e eventos.
        add("love.window.setMode", "(width, height, flags?)");
        add("love.window.getMode", "()");
        add("love.window.setTitle", "(title)");
        add("love.window.getTitle", "()");
        add("love.window.setIcon", "(imageData)");
        add("love.window.setFullscreen", "(fullscreen, mode?)");
        add("love.window.hasFocus", "()");
        add("love.window.getPosition", "()");
        add("love.window.setPosition", "(x, y, display?)");
        add("love.window.getDisplayCount", "()");
        add("love.window.getDesktopDimensions", "(display?)");
        add("love.window.showFileDialog", "(type, callback, path?, filetypes?, restrict?)");
        add("love.window.getSystemTheme", "()");
        add("love.timer.getDelta", "()");
        add("love.timer.getFPS", "()");
        add("love.timer.getTime", "()");
        add("love.timer.sleep", "(seconds)");
        add("love.timer.step", "()");
        add("love.timer.getAverageDelta", "()");
        add("love.event.quit", "(exitstatus?)");
        add("love.event.push", "(name, ...)");
        add("love.event.pump", "()");
        add("love.event.poll", "()");
        add("love.event.wait", "()");
        add("love.event.restart", "()");
        add("love.system.getOS", "()");
        add("love.system.getProcessorCount", "()");
        add("love.system.openURL", "(url)");
        add("love.system.getClipboardText", "()");
        add("love.system.setClipboardText", "(text)");
        add("love.system.getPreferredLocales", "()");

        // Matematica, dados, imagem, video e threads.
        add("love.math.random", "(max?) ou (min, max)");
        add("love.math.randomNormal", "(stddev?, mean?)");
        add("love.math.newRandomGenerator", "(seed?)");
        add("love.math.setRandomSeed", "(seed)");
        add("love.math.getRandomSeed", "()");
        add("love.math.noise", "(x, y?, z?, w?)");
        add("love.math.triangulate", "(polygon)");
        add("love.math.newTransform", "(x?, y?, angle?, sx?, sy?, ox?, oy?, kx?, ky?)");
        add("love.math.gammaToLinear", "(color)");
        add("love.math.linearToGamma", "(color)");
        add("love.data.newByteData", "(size)");
        add("love.data.newDataView", "(data, offset?, size?)");
        add("love.data.compress", "(container, format, data, level?)");
        add("love.data.decompress", "(container, compressedData)");
        add("love.data.encode", "(container, format, data)");
        add("love.data.decode", "(container, format, source)");
        add("love.data.hash", "(hashFunction, data)");
        add("love.image.newImageData", "(width, height?)");
        add("love.image.newImageData", "(filename)");
        add("love.sound.newSoundData", "(filename)");
        add("love.sound.newSoundData", "(samples, rate, bits, channels)");
        add("love.video.newVideoStream", "(filename)");
        add("love.thread.newThread", "(filename)");
        add("love.thread.newChannel", "()");
        add("love.thread.getChannel", "(name)");
        add("love.physics.newWorld", "(gravityX, gravityY, allowSleep?)");
        add("love.physics.newBody", "(world, x?, y?, type?)");
        add("love.physics.newCircleShape", "(radius)");
        add("love.physics.newRectangleShape", "(width, height)");
        add("love.physics.newPolygonShape", "(vertices)");
        add("love.physics.newFixture", "(body, shape, density?)");

        // Metodos de objetos comuns (o tipo do objeto e inferido pelo usuario).
        add("getWidth", "()");
        add("getHeight", "()");
        add("getDimensions", "()");
        add("getPosition", "()");
        add("setPosition", "(x, y)");
        add("getAngle", "()");
        add("setAngle", "(angle)");
        add("getRotation", "()");
        add("setRotation", "(angle)");
        add("getScale", "()");
        add("setScale", "(sx, sy?)");
        add("getColor", "()");
        add("setColor", "(r, g, b, a?)");
        add("getFilter", "()");
        add("setFilter", "(min, mag, anisotropy?)");
        add("getWrap", "()");
        add("setWrap", "(horizontal, vertical?)");
        add("getVelocity", "()");
        add("setVelocity", "(x, y)");
        add("getLinearVelocity", "()");
        add("setLinearVelocity", "(x, y)");
        add("applyForce", "(fx, fy, x?, y?)");
        add("applyLinearImpulse", "(ix, iy, x?, y?)");
        add("getMass", "()");
        add("setMass", "(mass)");
        add("getType", "()");
        add("setType", "(type)");
        add("play", "()");
        add("pause", "()");
        add("stop", "()");
        add("resume", "()");
        add("isPlaying", "()");
        add("isPaused", "()");
        add("seek", "(offset, unit?)");
        add("tell", "(unit?)");
        add("setVolume", "(volume)");
        add("getVolume", "()");
        add("send", "(name, value)");
        add("sendColor", "(name, color)");
        add("sendMatrix", "(name, matrix)");
        add("sendTexture", "(name, texture)");
        add("add", "(x, y, ...)");
        add("clear", "()");
        add("set", "(text)");
        add("getString", "()");
        add("getSize", "()");
        add("getFilename", "()");
        add("isDestroyed", "()");
        add("destroy", "()");
        
        // Funcoes Lua usadas com frequencia no editor.
        add("require", "(moduleName)");
        add("print", "(...)");
        add("pairs", "(table)");
        add("ipairs", "(table)");
        add("type", "(value)");
        add("tostring", "(value)");
        add("tonumber", "(value, base?)");
        add("assert", "(value, message?)");
        add("error", "(message, level?)");
        add("pcall", "(function, ...)");
        add("xpcall", "(function, messageHandler, ...)");
        add("select", "(index, ...)");
        add("setmetatable", "(table, metatable)");
        add("getmetatable", "(value)");
        add("rawget", "(table, index)");
        add("rawset", "(table, index, value)");
        add("next", "(table, index?)");
        add("collectgarbage", "(option?, arg?)");
    }

    private LoveApiParameters() {
    }

    private static void add(String name, String signature) {
        SIGNATURES.put(name, signature);
    }

    static String signature(String function) {
        if (function == null) return null;
        String exact = SIGNATURES.get(function);
        if (exact != null) return exact;

        // Para chamadas de metodo (objeto:metodo), usa assinatura generica
        // quando nao existe uma assinatura especifica para a classe.
        int colon = function.lastIndexOf(':');
        if (colon >= 0 && colon + 1 < function.length()) {
            return SIGNATURES.get(function.substring(colon + 1));
        }
        return null;
    }
}
