-- CodeLove: usa OpenGL no runtime Android por compatibilidade.
-- O uso de Vulkan causou falha de driver Mali durante a criacao de framebuffer.
-- Mantemos a restricao ate haver uma alternativa validada em dispositivos reais.

function love.conf(t)
    t.graphics = t.graphics or {}
    t.graphics.renderers = { "opengl" }
end
