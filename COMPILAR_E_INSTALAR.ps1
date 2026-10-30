# Mini Steve / Mini Alex - Windows one-click compiler and installer
# This ZIP does not contain an already compiled Minecraft mod.
# The script downloads official prerequisites, builds, checks, and installs the JAR.
$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -LiteralPath $root
$cache = Join-Path $root '.cache_do_instalador'
$log = Join-Path $root 'ERRO_DA_COMPILACAO.log'
$jarName = 'mini-companheiros-1.16.5-0.3.0.jar'

function Note([string]$message) {
    Write-Host ('[Mini Steve/Alex] ' + $message) -ForegroundColor Cyan
}
function Fetch([string]$url, [string]$dest) {
    Note ('Baixando arquivo oficial: ' + $url)
    Invoke-WebRequest -Uri $url -OutFile $dest -UseBasicParsing -MaximumRedirection 10
    if (-not (Test-Path -LiteralPath $dest) -or (Get-Item $dest).Length -lt 1000) {
        throw ('O download esta incompleto: ' + $dest)
    }
}
function JdkHome([string]$dir) {
    if (-not $dir) { return $null }
    $java = Join-Path $dir 'bin\java.exe'
    $javac = Join-Path $dir 'bin\javac.exe'
    if ((Test-Path $java) -and (Test-Path $javac)) {
        $versionOutput = & $javac -version 2>&1 | Out-String
        if ($versionOutput -match 'javac 1\.8\.') { return $dir }
    }
    return $null
}

try {
    Note 'Alvo: Minecraft 1.16.5 + Forge 36.1.0 + OptiFine HD U G8.'
    Note 'Para este primeiro build, sera necessaria uma conexao com a internet.'
    New-Item -ItemType Directory -Path $cache -Force | Out-Null

    # Use Java 8 if installed; otherwise download official portable Eclipse Temurin JDK 8.
    $jdk = JdkHome $env:JAVA_HOME
    if (-not $jdk) {
        $installedJavac = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($installedJavac) { $jdk = JdkHome (Split-Path -Parent (Split-Path -Parent $installedJavac.Source)) }
    }
    if (-not $jdk) {
        $jdkDir = Join-Path $cache 'jdk8'
        if (Test-Path $jdkDir) {
            $candidates = @(Get-ChildItem -LiteralPath $jdkDir -Filter 'javac.exe' -File -Recurse -ErrorAction SilentlyContinue)
            foreach ($candidate in $candidates) {
                $jdk = JdkHome (Split-Path -Parent (Split-Path -Parent $candidate.FullName))
                if ($jdk) { break }
            }
        }
    }
    if (-not $jdk) {
        Note 'Java 8 JDK nao localizado. Baixando JDK 8 portatil do Eclipse Adoptium...'
        $jdkZip = Join-Path $cache 'temurin-jdk8.zip'
        if (-not (Test-Path $jdkZip)) {
            Fetch 'https://api.adoptium.net/v3/binary/latest/8/ga/windows/x64/jdk/hotspot/normal/eclipse' $jdkZip
        }
        $jdkDir = Join-Path $cache 'jdk8'
        New-Item -ItemType Directory -Force -Path $jdkDir | Out-Null
        Expand-Archive -LiteralPath $jdkZip -DestinationPath $jdkDir -Force
        $candidates = @(Get-ChildItem -LiteralPath $jdkDir -Filter 'javac.exe' -File -Recurse)
        foreach ($candidate in $candidates) {
            $jdk = JdkHome (Split-Path -Parent (Split-Path -Parent $candidate.FullName))
            if ($jdk) { break }
        }
    }
    if (-not $jdk) { throw 'Nao encontrei um JDK 8 valido. Verifique sua conexao e tente novamente.' }
    $env:JAVA_HOME = $jdk
    $env:PATH = (Join-Path $jdk 'bin') + ';' + $env:PATH
    Note ('Java 8: ' + $jdk)

    # Testa regras e sintaxe Java antes de iniciar o download pesado do Forge.
    Note 'Conferindo regras do jogo e sintaxe Java 8...'
    $testClasses = Join-Path $cache 'classes_de_teste'
    New-Item -ItemType Directory -Path $testClasses -Force | Out-Null
    $compiler = Join-Path $jdk 'bin\javac.exe'
    $runner = Join-Path $jdk 'bin\java.exe'
    $rulesSource = Join-Path $root 'src\main\java\br\com\minicompanheiros\Regras.java'
    $rulesTest = Join-Path $root 'tests\TestarRegras.java'
    $brainSource = Join-Path $root 'src\main\java\br\com\minicompanheiros\Cerebro.java'
    $brainTest = Join-Path $root 'tests\TestarCerebro.java'
    $syntaxTest = Join-Path $root 'tests\TestarSintaxe.java'
    & $compiler -source 8 -target 8 -d $testClasses $rulesSource $brainSource $rulesTest $brainTest $syntaxTest
    if ($LASTEXITCODE -ne 0) { throw 'O codigo Java dos testes nao compilou.' }
    $testClasspath = $testClasses + ';' + (Join-Path $jdk 'lib\tools.jar')
    & $runner -cp $testClasspath TestarRegras
    if ($LASTEXITCODE -ne 0) { throw 'Falharam os testes locais de fome e ferramentas.' }
    & $runner -cp $testClasspath TestarCerebro
    if ($LASTEXITCODE -ne 0) { throw 'Falharam os testes locais de IA.' }
    & $runner -cp $testClasspath TestarSintaxe (Join-Path $root 'src\main\java')
    if ($LASTEXITCODE -ne 0) { throw 'Falhou a verificacao de sintaxe Java.' }
    Note 'Testes Java offline e IA aprovados. Preparando o Forge...'

    # Download the matching, official Forge 36.1.0 MDK to obtain its Gradle wrapper.
    $wrapper = Join-Path $root 'gradlew.bat'
    $wrapperJar = Join-Path $root 'gradle\wrapper\gradle-wrapper.jar'
    if ((-not (Test-Path $wrapper)) -or (-not (Test-Path $wrapperJar))) {
        Note 'Preparando Gradle pelo kit oficial Forge 36.1.0...'
        $mdkZip = Join-Path $cache 'forge-1.16.5-36.1.0-mdk.zip'
        if (-not (Test-Path $mdkZip)) {
            Fetch 'https://maven.minecraftforge.net/net/minecraftforge/forge/1.16.5-36.1.0/forge-1.16.5-36.1.0-mdk.zip' $mdkZip
        }
        $mdkDir = Join-Path $cache 'mdk_36_1_0'
        New-Item -ItemType Directory -Path $mdkDir -Force | Out-Null
        Expand-Archive -LiteralPath $mdkZip -DestinationPath $mdkDir -Force
        $mdkWrapper = Get-ChildItem -LiteralPath $mdkDir -Filter 'gradlew.bat' -Recurse -File | Select-Object -First 1
        if (-not $mdkWrapper) { throw 'Kit Forge nao possui gradlew.bat. Download possivelmente invalido.' }
        $mdkRoot = Split-Path -Parent $mdkWrapper.FullName
        Copy-Item -LiteralPath (Join-Path $mdkRoot 'gradlew.bat') -Destination $wrapper -Force
        Copy-Item -LiteralPath (Join-Path $mdkRoot 'gradlew') -Destination (Join-Path $root 'gradlew') -Force
        Copy-Item -LiteralPath (Join-Path $mdkRoot 'gradle') -Destination (Join-Path $root 'gradle') -Recurse -Force
    }
    if (-not (Test-Path $wrapperJar)) { throw 'O Gradle Wrapper esta incompleto.' }

    Note 'Compilando o mod (o Gradle vai baixar as bibliotecas necessarias)...'
    $oldPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $wrapper --no-daemon build *> $log
        $buildExit = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
    }
    if ($buildExit -ne 0) {
        Write-Host 'Ultimas mensagens de compilacao:' -ForegroundColor Yellow
        Get-Content -LiteralPath $log -Tail 35 | ForEach-Object { Write-Host $_ }
        throw ('Falha na compilacao. Registro completo salvo em ' + $log)
    }

    $jar = Join-Path $root ('build\libs\' + $jarName)
    if (-not (Test-Path $jar)) { throw ('A compilacao terminou, mas o JAR nao apareceu: ' + $jar) }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try {
        $names = @($zip.Entries | ForEach-Object { $_.FullName })
        foreach ($required in @('META-INF/mods.toml', 'br/com/minicompanheiros/MiniCompanheiros.class', 'br/com/minicompanheiros/CompanheiroEntity.class')) {
            if ($names -notcontains $required) { throw ('O JAR nao tem arquivo essencial: ' + $required) }
        }
    } finally { $zip.Dispose() }
    Note ('JAR construido e estrutura verificada: ' + $jarName)

    $defaultMods = Join-Path $env:APPDATA '.minecraft\mods'
    Write-Host ''
    Write-Host ('Pasta mods padrao: ' + $defaultMods) -ForegroundColor White
    Write-Host 'Se seu TLauncher usa outra pasta, cole o caminho dela abaixo.'
    $answer = Read-Host 'ENTER para usar a pasta padrao (ou informe outro caminho)'
    $mods = if ([string]::IsNullOrWhiteSpace($answer)) { $defaultMods } else { [Environment]::ExpandEnvironmentVariables($answer.Trim().Trim('"')) }
    New-Item -ItemType Directory -Path $mods -Force | Out-Null
    $oldMods = @(Get-ChildItem -LiteralPath $mods -Filter 'mini-companheiros*.jar' -File)
    if ($oldMods.Count -gt 0) {
        $backup = Join-Path $mods ('backup_mini_companheiros_' + (Get-Date -Format 'yyyyMMdd_HHmmss'))
        New-Item -ItemType Directory -Path $backup -Force | Out-Null
        foreach ($old in $oldMods) { Move-Item -LiteralPath $old.FullName -Destination $backup -Force }
        Note 'Versoes antigas guardadas em uma pasta de backup.'
    }
    Copy-Item -LiteralPath $jar -Destination (Join-Path $mods $jarName) -Force
    Write-Host ''
    Write-Host 'SUCESSO: o JAR foi copiado para a pasta mods!' -ForegroundColor Green
    Write-Host ('Arquivo: ' + (Join-Path $mods $jarName))
    Write-Host 'Use o perfil Forge 1.16.5 - 36.1.0 no TLauncher.'
    Write-Host 'OptiFine HD U G8 deve ser baixado separadamente pelo site oficial.'
    Write-Host 'Teste em um mundo novo antes de usar seu mundo survival.'
    exit 0
} catch {
    Write-Host ''
    Write-Host ('FALHA: ' + $_.Exception.Message) -ForegroundColor Red
    Write-Host 'Se o erro foi de compilacao, abra ERRO_DA_COMPILACAO.log.'
    Write-Host 'Nao foi instalado nenhum mod incompleto.'
    exit 1
}
