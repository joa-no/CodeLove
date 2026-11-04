-- CodeLove: teste controlado para evitar o caminho Vulkan no Android.
-- O crash diagnosticado ocorreu dentro do driver Mali durante a criacao
-- de um framebuffer Vulkan. Mantemos apenas OpenGL neste build de teste.

function love.conf(t)
    t.graphics = t.graphics or {}
    t.graphics.renderers = { "opengl" }
end
