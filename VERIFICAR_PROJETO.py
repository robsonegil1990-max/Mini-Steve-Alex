#!/usr/bin/env python3
"""Verifica integridade de recursos e parametros: nao substitui o build Forge."""
from pathlib import Path
import re, json, struct

root = Path(__file__).resolve().parent
build = (root / 'build.gradle').read_text(encoding='utf-8')
mods = (root / 'src/main/resources/META-INF/mods.toml').read_text(encoding='utf-8')
installer = (root / 'COMPILAR_E_INSTALAR.ps1').read_text(encoding='utf-8')
renderer = (root / 'src/main/java/br/com/minicompanheiros/client/RenderCompanheiro.java').read_text(encoding='utf-8')
entity = (root / 'src/main/java/br/com/minicompanheiros/CompanheiroEntity.java').read_text(encoding='utf-8')
ui = (root / 'src/main/java/br/com/minicompanheiros/client/TelaCompanheiro.java').read_text(encoding='utf-8')
alvos = (root / 'src/main/java/br/com/minicompanheiros/Alvos.java').read_text(encoding='utf-8')

assert "net.minecraftforge:forge:1.16.5-36.1.0" in build
assert "version = '0.3.0'" in build
assert 'versionRange="[36.1.0,37)"' in mods
assert 'modId="minicompanheiros"' in mods
assert '1.16.5-0.3.0.jar' in installer
assert 'HeldItemLayer' in renderer and 'PlayerModel<' in renderer
assert 'net.optifine' not in renderer and 'org.spongepowered.asm.mixin' not in renderer
assert 'setDropChance(EquipmentSlotType.MAINHAND, -1.0F)' in entity
for fragment in ('isAutonomous', 'toggleAutonomous', 'Cerebro.escolherTarefa', 'eatFromOwnBag', 'safeStand', 'rememberFailedTarget',
                 'hasLeavesNearby', 'folhagemNoNivel', 'getFoodLevel', 'returnToOwner', 'toggleFollow',
                 'feedFromInventory', 'sendState', 'pickupPosition', 'attackedCurrentTarget'):
    assert fragment in entity, fragment
for fragment in ('ALTERNAR_IA', 'IA: LIGADA', 'renderEntityInInventory', 'Mochila', 'Alimentar', 'Monstros', 'renderGuiItemDecorations'):
    assert fragment in ui, fragment

# As listas do menu precisam ter mesmo tamanho dos alvos e dos requisitos de picareta.
def count_values(name):
    match = re.search(r'\b' + name + r'\s*=\s*\{(.*?)\}', alvos, re.S)
    assert match, 'Nao achei a lista ' + name
    values = re.findall(r'"(?:[^"\\]|\\.)*"|\b(?:Blocks|EntityType)\.\w+|(?<!\w)\d+\b', match.group(1))
    return len(values)

assert count_values('MINERIOS') == count_values('NOMES_MINERIOS') == count_values('NIVEL_PICARETA') == 10
assert count_values('MADEIRAS') == count_values('NOMES_MADEIRAS') == 8
assert count_values('ANIMAIS') == count_values('NOMES_ANIMAIS') == 5
assert count_values('MONSTROS_TIPOS') == count_values('NOMES_MONSTROS') == 6

for item, center in (('mini_steve', 'minecraft:armor_stand'), ('mini_alex', 'minecraft:carved_pumpkin')):
    path = root / 'src/main/resources/data/minicompanheiros/recipes' / (item + '.json')
    recipe = json.loads(path.read_text('utf-8'))
    assert recipe['type'] == 'minecraft:crafting_shaped'
    assert recipe['result']['item'] == 'minicompanheiros:' + item
    assert recipe['key']['C']['item'] == center
    assert 'minecraft:diamond' in [k['item'] for k in recipe['key'].values()]
    assert 'minecraft:netherite_scrap' in [k['item'] for k in recipe['key'].values()]
    texture = root / 'src/main/resources/assets/minicompanheiros/textures/item' / (item + '.png')
    data = texture.read_bytes()
    assert data[:8] == b'\x89PNG\r\n\x1a\n'
    assert struct.unpack('>II', data[16:24]) in ((16, 16), (32, 32)), texture
    model = root / 'src/main/resources/assets/minicompanheiros/models/item' / (item + '.json')
    parsed = json.loads(model.read_text('utf-8'))
    assert 'minicompanheiros:item/' + item in parsed['textures']['layer0']

for p in (root / 'src/main/resources').rglob('*.json'):
    json.loads(p.read_text('utf-8'))
assert json.loads((root / 'src/main/resources/pack.mcmeta').read_text('utf-8'))['pack']['pack_format'] == 6
source_files = list((root / 'src/main/java').rglob('*.java'))
assert len(source_files) >= 13
print('OK: receitas, indices de menu, PNGs 16x16/32x32, metadados e versoes validados.')
packets = (root / 'src/main/java/br/com/minicompanheiros/network/MensagemEstado.java').read_text(encoding='utf-8')
commands = (root / 'src/main/java/br/com/minicompanheiros/network/MensagemOrdem.java').read_text(encoding='utf-8')
channel = (root / 'src/main/java/br/com/minicompanheiros/network/Messages.java').read_text(encoding='utf-8')
brain = (root / 'src/main/java/br/com/minicompanheiros/Cerebro.java').read_text(encoding='utf-8')
assert 'boolean autonomous' in packets and 'writeBoolean(m.autonomous)' in packets and 'buf.readBoolean()' in packets
assert 'ALTERNAR_IA = 8' in commands and 'helper.toggleAutonomous(player)' in commands
assert 'PROTOCOL = "3"' in channel and 'escolherTarefa' in brain
print('OK: interface de IA e protocolo de rede de status/comandos consistentes.')
print('AVISO: teste offline nao verifica compilacao com bibliotecas Forge nem execucao no Minecraft.')
