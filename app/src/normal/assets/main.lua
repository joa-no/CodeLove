-- CodeLove: executor (runtime), versao 1.
--
-- Este arquivo roda dentro do LOVE o tempo todo. Ele carrega o projeto escolhido
-- no painel SEM reiniciar o app, desenha o primeiro quadro (pausado) e so mexe
-- o jogo quando o play e apertado.
--
-- Conversa com o painel (Java) por dois arquivos na pasta do "canal":
--   commands.txt  (o painel escreve)  -> uma linha por comando: nome<TAB>argumento
--   status.txt    (este arquivo escreve) -> a ultima mensagem, mostrada no painel

local VERSION = 1
local POLL_INTERVAL = 0.2 -- de quanto em quanto tempo olha o arquivo de comandos

local host = {
  channelDir = nil,  -- pasta do canal (onde ficam commands.txt e status.txt)
  processed = 0,     -- quantas linhas de commands.txt ja foram tratadas
  pollTimer = 0,
  project = nil,     -- { path, name, callbacks, mounted }
  playing = false,
  errorText = nil,
  baseModules = {},  -- modulos que ja existiam antes de qualquer projeto
  errorFont = nil,
}

-- ----------------------------------------------------------------------
-- Arquivos do canal
-- ----------------------------------------------------------------------

local function readFile(path)
  local f = io.open(path, "rb")
  if not f then return nil end
  local data = f:read("*a")
  f:close()
  return data
end

local function writeStatus(text)
  if not host.channelDir then return end
  local f = io.open(host.channelDir .. "/status.txt", "wb")
  if f then
    f:write(text)
    f:close()
  end
end

local function firstLine(text)
  return (tostring(text):match("^[^\n]*"))
end

local function findChannelDir()
  -- 1) caminho enviado pelo painel como argumento
  if type(arg) == "table" then
    for i = 1, #arg do
      local a = arg[i]
      if type(a) == "string" and a:sub(1, 1) == "/" and a:find("codelove", 1, true) then
        return a
      end
    end
  end
  -- 2) plano B: deduz a partir da pasta de save do LOVE
  local ok, save = pcall(love.filesystem.getSaveDirectory)
  if ok and type(save) == "string" then
    local base = save:match("^(.*/files)/")
    if base then return base .. "/codelove" end
  end
  return nil
end

-- ----------------------------------------------------------------------
-- Projeto aberto
-- ----------------------------------------------------------------------

local function fail(err)
  host.errorText = tostring(err)
  host.playing = false
  writeStatus("erro: " .. firstLine(err))
end

local function callback(name)
  local p = host.project
  if not p then return nil end
  -- rawget: a tabela de callbacks "herda" do love, e nao queremos pegar as
  -- funcoes do proprio executor por engano.
  local fn = rawget(p.callbacks, name)
  if type(fn) == "function" then return fn end
  return nil
end

local function closeProject()
  local p = host.project
  if not p then return end

  -- esquece os modulos (require) carregados pelo projeto
  for name in pairs(package.loaded) do
    if not host.baseModules[name] then
      package.loaded[name] = nil
    end
  end

  if p.mounted then
    pcall(love.filesystem.unmountFullPath, p.path)
  end

  host.project = nil
  host.playing = false
  host.errorText = nil
end

local function newProjectEnvironment()
  -- "love" do projeto: le tudo do love de verdade, mas o que o projeto define
  -- (love.load, love.update, love.draw...) fica guardado nesta tabela.
  local callbacks = setmetatable({}, { __index = love })
  -- love.event.quit() do projeto nao pode fechar o editor
  callbacks.event = setmetatable({ quit = function() end }, { __index = love.event })
  local env = setmetatable({ love = callbacks }, { __index = _G })
  return callbacks, env
end

local function openProject(path)
  closeProject()
  host.errorText = nil
  host.playing = false

  local name = path:match("([^/]+)/*$") or path

  local code = readFile(path .. "/main.lua")
  if not code then
    host.project = { path = path, name = name, callbacks = {}, mounted = false }
    host.errorText = "O projeto " .. name .. " nao tem main.lua."
    writeStatus("erro: o projeto " .. name .. " nao tem main.lua")
    return
  end

  -- deixa os arquivos do projeto (imagens, require...) visiveis ao love.filesystem
  local okMount, mounted = pcall(love.filesystem.mountFullPath, path, "", "read", false)
  mounted = okMount and mounted and true or false

  local callbacks, env = newProjectEnvironment()
  host.project = { path = path, name = name, callbacks = callbacks, mounted = mounted }

  local chunk, err
  if setfenv then
    chunk, err = loadstring(code, "@main.lua")
    if chunk then setfenv(chunk, env) end
  else
    chunk, err = load(code, "@main.lua", "t", env)
  end
  if not chunk then return fail(err) end

  local ok, runErr = xpcall(chunk, debug.traceback)
  if not ok then return fail(runErr) end

  local loadFn = callback("load")
  if loadFn then
    local ok2, err2 = xpcall(loadFn, debug.traceback, {}, {})
    if not ok2 then return fail(err2) end
  end

  if mounted then
    writeStatus("projeto aberto: " .. name .. " (pausado)")
  else
    writeStatus("aviso: " .. name .. " aberto sem acesso aos arquivos do projeto (imagens, require)")
  end
end

local function setPlaying(flag)
  if not host.project or host.errorText then return end
  host.playing = flag
  writeStatus(flag and "jogando" or "pausado")
end

-- ----------------------------------------------------------------------
-- Comandos vindos do painel
-- ----------------------------------------------------------------------

local function handleCommand(command, argument)
  if command == "open" then
    openProject(argument)
  elseif command == "play" then
    setPlaying(true)
  elseif command == "pause" then
    setPlaying(false)
  elseif command == "close" then
    closeProject()
    writeStatus("nenhum projeto aberto")
  end
end

local function pollCommands()
  if not host.channelDir then return end
  local data = readFile(host.channelDir .. "/commands.txt")
  if not data or data == "" then return end

  local index = 0
  -- so linhas completas (terminadas em \n)
  for line in data:gmatch("([^\n]*)\n") do
    index = index + 1
    if index > host.processed then
      host.processed = index
      local command, argument = line:match("^([^\t]*)\t?(.*)$")
      handleCommand(command, argument)
    end
  end
end

-- ----------------------------------------------------------------------
-- Callbacks do LOVE
-- ----------------------------------------------------------------------

function love.load()
  for name in pairs(package.loaded) do
    host.baseModules[name] = true
  end
  host.errorFont = love.graphics.newFont(20)
  host.channelDir = findChannelDir()

  if host.channelDir then
    writeStatus("executor pronto (v" .. VERSION .. ")")
  end
end

function love.update(dt)
  host.pollTimer = host.pollTimer + dt
  if host.pollTimer >= POLL_INTERVAL then
    host.pollTimer = 0
    local ok, err = pcall(pollCommands)
    if not ok then writeStatus("erro interno: " .. firstLine(err)) end
  end

  if host.project and host.playing and not host.errorText then
    local fn = callback("update")
    if fn then
      local ok, err = xpcall(fn, debug.traceback, dt)
      if not ok then fail(err) end
    end
  end
end

local function drawError()
  local w = love.graphics.getWidth()
  love.graphics.clear(0.35, 0.05, 0.05)
  love.graphics.setColor(1, 1, 1)
  love.graphics.setFont(host.errorFont)
  love.graphics.printf("ERRO NO PROJETO\n\n" .. host.errorText, 16, 16, w - 32, "left")
end

function love.draw()
  if not host.project then return end
  love.graphics.reset()

  if host.errorText then
    drawError()
    return
  end

  local fn = callback("draw")
  if fn then
    local ok, err = xpcall(fn, debug.traceback)
    if not ok then fail(err) end
  end
end

-- Entradas (toque, teclado...): so chegam ao projeto quando ele esta jogando.
local forwarded = {
  "keypressed", "keyreleased", "textinput",
  "mousepressed", "mousereleased", "mousemoved", "wheelmoved",
  "touchpressed", "touchreleased", "touchmoved",
}

for _, name in ipairs(forwarded) do
  love[name] = function(...)
    if host.project and host.playing and not host.errorText then
      local fn = callback(name)
      if fn then
        local ok, err = xpcall(fn, debug.traceback, ...)
        if not ok then fail(err) end
      end
    end
  end
end
