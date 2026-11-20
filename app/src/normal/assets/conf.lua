-- CodeLove preview: read the queued project before LOVE creates its window.
-- Keep the host modules and OpenGL compatibility policy intact.
local function queuedProject()
    local args = love.parsedGameArguments or arg or {}
    for _, channel in pairs(args) do
        if type(channel) == "string" and channel:sub(1, 1) == "/" then
            local f = io.open(channel .. "/commands/command-0000000001.txt", "rb")
            if f then
                local data = f:read("*a")
                f:close()
                local path = data:match("^open\t([^\t]+)\t")
                if path then return path end
            end
        end
    end
end

function love.conf(t)
    local path = queuedProject()
    if path then
        -- No explicit fixed size: allow the device's orientation.
        t.window.resizable = true
        local f = io.open(path .. "/conf.lua", "rb")
        if f then
            local code = f:read("*a")
            f:close()
            local projectLove = setmetatable({}, { __index = love })
            local env = setmetatable({ love = projectLove }, { __index = _G })
            local chunk, err = loadstring(code, "@project/conf.lua")
            if not chunk then error(err) end
            setfenv(chunk, env)
            chunk()
            if type(rawget(projectLove, "conf")) == "function" then
                local config = {}
                for k, v in pairs(t) do
                    if type(v) == "table" then
                        local copy = {}
                        for key, value in pairs(v) do copy[key] = value end
                        config[k] = copy
                    else
                        config[k] = v
                    end
                end
                -- Fixed dimensions use LOVE's standard defaults unless the
                -- project explicitly opts into resizable mode.
                config.window.resizable = false
                projectLove.conf(config)
                if type(config.window) == "table" then
                    local window = config.window
                    -- The preview must retain its window; asset icon loading
                    -- is deferred until project files are mounted by main.lua.
                    window.icon = nil
                    t.window = window
                end
            end
        end
    end
    t.graphics = t.graphics or {}
    t.graphics.renderers = { "opengl" }
end
