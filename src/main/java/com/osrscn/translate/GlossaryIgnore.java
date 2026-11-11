package com.osrscn.translate;

import java.util.Set;

/**
 * English NAME-table terms that must never be injected as terminology hints.
 *
 * <p>The NAME table also contains ordinary English words ({@code Well}, {@code Safe},
 * {@code Coins}, ...). Hinting those as terminology pushes the model towards the entity
 * reading of an ordinary sentence, so they are dropped before the glossary index is built.
 *
 * <p>Human-reviewed selection (2026-11-09) from a generated candidate list (1,014 of
 * 1,643 candidates kept), plus {@code will} and nine entity-name or mistranslation rows
 * added after the branch review (1,024 terms total). Terms are lower-case and matched as
 * whole terms by {@link TermGlossary}; the ignore check also covers regular plural,
 * possessive and irregular plural variants, so the list only needs the dictionary form
 * of those. The order is alphabetical only for review.
 */
final class GlossaryIgnore
{
	static final Set<String> TERMS = Set.of(
			"a chair", "a corpse", "a goblin", "a rock", "a wall", "academic", "acorn", "advert", "advisor", "agility",
			"albatross", "allotment", "alpaca", "altar", "amethyst", "anchor", "angel", "anvil", "apothecary", "apprentice",
			"aquarium", "arch", "archer", "archway", "armour", "arms", "arrow", "arrowheads", "arrows", "artefact",
			"artefacts", "ashes", "assassin", "assistant", "attack", "avalanche", "axes", "baboon", "backpack", "bags",
			"baker", "balcony", "ball", "banana", "bandages", "bandit", "bandits", "bank", "banker", "banner",
			"bard", "bark", "barley", "barman", "baron", "barrel", "barrels", "barricade", "barrier", "bars",
			"bartender", "basin", "basket", "bass", "bath", "bathtub", "bats", "beach", "beam", "beanie",
			"beard", "bears", "beaver", "bedroom", "beds", "bedsheet", "beef", "beer", "bees", "beetle",
			"bell", "bells", "bench", "berry", "big door", "bind", "bird", "birds", "birthday cake", "black",
			"blackboard", "blacksmith", "blade", "blood", "bloodhound", "blue", "blue moon", "bluebells", "boar", "boat",
			"body", "boil", "boiler", "bone", "bones", "bonfire", "book", "bookcase", "books", "bookshelf",
			"bookshelves", "boot", "bottle", "boulder", "bow and arrow", "bowl", "bowls", "boxes", "brain", "branch",
			"breach", "bread", "brick", "bricks", "bridge", "briefcase", "broken chair", "brooch", "broom", "brush",
			"bubbles", "bucket", "buckets", "buffalo", "bugs", "builder", "bull", "bunny", "bush", "bushes",
			"butler", "butterfly", "button", "buttons", "cabbage", "cabbages", "cabinet", "cable", "cactus", "cage",
			"cages", "cake", "camel", "campfire", "candle", "candles", "cannon", "cannonball", "cannonballs", "captain",
			"cargo crate", "carpenter", "carpet", "carrot", "cart", "casket", "casual", "catapult", "catfish", "cauldron",
			"cave", "cavern", "caviar", "certificate", "chain", "chains", "chair", "chamber", "chapel", "charcoal",
			"charge", "charms", "chart", "chasm", "cheese", "chef", "chess", "chest", "chicken", "chicken coop",
			"child", "chimney", "chisel", "chores", "chronicle", "cider", "circle", "citizen", "civilian", "clay",
			"cleaner", "clerk", "cliff", "clock", "cloth", "clothes", "coal", "cockroach", "coconut", "coffer",
			"coffin", "coins", "coke", "column", "combination", "compass", "competitive", "conductor", "confession", "confuse",
			"console", "construction", "container", "cook", "cooker", "cooking", "cooler", "coral", "core", "corpse",
			"corpses", "cotton", "counter", "cows", "crab", "crack", "crackers", "crafting", "crane", "crate",
			"crater", "crates", "creature", "crocodile", "crocodiles", "cross", "crossbow", "crow", "crucible", "crypt",
			"crystal", "cube", "cup of tea", "cupboard", "curator", "current", "curry", "curse", "curtain", "curtains",
			"cushion", "customer", "customer support", "cylinder", "daffodils", "dart", "dawn", "dead body", "death", "debris",
			"declaration", "decoration", "decorations", "deed", "defence", "demon", "desert", "desk", "device", "dial",
			"diamond", "diary", "dice", "dictionary", "dining room", "directions", "dirt", "dishes", "display", "ditch",
			"documents", "dogs", "doll", "dolphin", "don't know what", "door", "doors", "doorway", "dossier", "dragon",
			"dragonfly", "dragonfruit", "drain", "drapes", "drawers", "dream", "dresser", "drill", "drum", "drummer",
			"duck", "duke", "dummy", "dung", "dusk", "dust", "dwarf", "dynamite", "eagle", "easter",
			"edge", "eggs", "eight", "elevator", "elves", "emissary", "engine", "engineer", "entrance", "entry",
			"equipment", "exile", "exit", "experiment", "explosion", "eyes", "fabric", "fang", "farmer", "farming",
			"farming patch", "feather", "feathers", "fedora", "fence", "fern", "ferret", "ferry", "fighter", "file",
			"fillets", "fingers", "fire", "fireplace", "firework", "fish", "fishbowl", "fisher", "fisherman", "fishing",
			"fishing net", "fishing rod", "five", "flag", "flames", "flax", "fletching", "flies", "floor", "flour",
			"flower", "flowers", "flute", "flyer", "font", "footprint", "footprints", "foreman", "forge", "fountain",
			"four", "frame", "frog", "front door", "fungi", "fungus", "furnace", "fuse", "gangster", "garden",
			"gardener", "garlic", "gate", "gatekeeper", "gates", "gear", "generator", "ghost", "ghosts", "giant",
			"gingerbread", "globe", "goal", "goat", "gold", "golem", "goose", "gorilla", "gossip", "grace",
			"grain", "grapes", "grapevine", "grapevines", "grapple", "grass", "grave", "gravestone", "green", "grew",
			"grey", "grill", "grip", "growth", "guard", "guitar", "hair", "hairdresser", "halberd", "hall",
			"hammer", "hammers", "hammock", "handkerchief", "handle", "hanger", "hanging", "harmony", "harp", "harpoon",
			"harvester", "haze", "head", "heat", "heater", "heather", "hedge", "helm", "herb", "herbs",
			"hero", "heron", "hill", "history", "hole", "hoop", "hopper", "horn", "hourglass", "house",
			"hunter", "hurdle", "ice block", "iceberg", "icon", "idol", "information", "insane", "intelligence", "isles",
			"item", "jade", "jelly", "jellyfish", "jester", "jewellery", "jewels", "journal", "junk", "jury",
			"jute", "kebab", "kettle", "keyhole", "keys", "keystone", "killer", "king", "kitchen", "kitten",
			"knife", "knight", "ladder", "lamb", "lamp", "lamppost", "landscape", "lantern", "lava", "leaflets",
			"leather", "leaves", "leftovers", "legs", "lemon", "letter", "level", "lever", "librarian", "lieutenant",
			"lift", "light", "lightning", "limestone", "list", "lizard", "lizards", "lock", "locked", "locked door",
			"locker", "lockers", "lockpick", "logs", "look-out", "loom", "lyre", "mace", "machete", "machine",
			"machinery", "magic", "magnet", "maid", "mama", "mammoths", "manual", "mark", "marker", "market stall",
			"mast", "mechanism", "memorial", "mercenary", "merchandise", "mercy", "mess", "message", "messenger", "metal",
			"meter", "midnight", "mill", "mimic", "minecart", "miner", "mining", "mirror", "mist", "mixer",
			"monk", "monkey", "monkeys", "monument", "mother", "mould", "mouse", "mummy", "mushroom", "mushrooms",
			"music", "my life", "nails", "navy", "needle", "nerve", "nerves", "nest", "nettles", "newspaper",
			"newspapers", "nine", "nomad", "noon", "note", "notes", "nothing", "notice", "novice", "nugget",
			"nuggets", "oak tree", "obelisk", "observations", "obstacle", "old man", "onion", "opal", "opening", "operator",
			"oracle", "orange", "orchid", "orchids", "organ", "ornament", "other", "outlaw", "oven", "pack",
			"package", "pages", "painting", "palm", "panel", "paper", "parasite", "parchment", "parrot", "passage",
			"paste", "path", "peace treaty", "peach", "peasant", "pebble", "pebbles", "pedals", "pendant", "pendulum",
			"penguin", "perch", "phantom", "pheasant", "phoenix", "piano", "pickaxe", "picnic", "picture", "pier",
			"pigeon", "piles", "pilgrim", "pillar", "pillow", "pillows", "pine", "pipe", "pipes", "pirate",
			"pirates", "pitfall", "plank", "planks", "plant", "plant pot", "plants", "plaque", "platform", "plough",
			"plunder", "poem", "poison", "pole", "pond", "port", "port task", "portal", "portrait", "post",
			"poster", "posts", "potato", "potatoes", "potion", "potions", "pots", "pottery", "pottery wheel", "prayer",
			"present", "preserve", "priest", "prisoner", "prosecutor", "protester", "pufferfish", "pump", "pumpkin", "purple",
			"pushup", "rabbit", "rack", "raft", "rail", "railing", "rain", "rake", "ramp", "range",
			"ranged", "ranger", "rapier", "rations", "rats", "reach", "rebel", "receipt", "receptionist", "recess",
			"recipe", "redemption", "refugee", "remains", "researcher", "retribution", "rhino", "ribs", "rift", "river",
			"rock", "rocks", "rocky", "rogue", "rogues", "roll", "roof", "root", "roots", "rope",
			"ropes", "rose", "rosemary", "roses", "ruby", "ruins", "sack", "sacks", "sacrifice", "safe",
			"sage", "sail", "sailor", "sails", "salmon", "sand", "sapphire", "satchel", "saucepan", "scarecrow",
			"schoolgirl", "scimitar", "scoreboard", "scorpion", "scorpions", "scout", "screen", "scroll", "scrolls", "sculpture",
			"scurry", "sea turtle", "seagull", "search", "seaweed", "secateurs", "secretary", "seeds", "sergeant", "servant",
			"seven", "shadow", "shark", "shed", "sheep", "shelf", "shell", "shelves", "shield", "ship",
			"shipwreck", "shirt", "shoe", "shoe.", "shoes", "shopkeeper", "shore", "shortcut", "shorts", "shot",
			"shrimp", "shrine", "shrub", "sign", "silk", "sink", "situp", "skeleton", "skeletons", "sketch",
			"skirt", "skis", "skull", "skulls", "slab", "slave", "slayer", "sled", "slope", "smite",
			"smith", "smithing", "smoke", "snake", "snakeskin", "snow", "snowball", "snowballs", "snowflake", "snowman",
			"sofa", "soil", "soldier", "sole", "sorceress", "soup", "space", "spade", "spanner", "spawn",
			"spear", "specimen", "spice", "spider", "spiders", "spike", "spikes", "spine", "spinning wheel", "spotlight",
			"spray", "squire", "squirrel", "staff", "staircase", "stairs", "stall", "stand", "standard", "stardust",
			"start", "statue", "statuette", "steps", "stew", "stick", "sticks", "stocks", "stone", "stones",
			"stool", "stove", "stranger", "straw", "strawberry", "stream", "strength", "stronghold", "student", "study",
			"stump", "stun", "suit of armour", "sulphur", "sunflower", "sunflowers", "supplies", "support", "surface", "suspect",
			"swamp", "swan", "sweet", "sweetcorn", "sweets", "swings", "switch", "swords", "symbol", "table",
			"tail", "target", "teacher", "teapot", "telescope", "tent", "tentacle", "terminal", "the desert", "the rocks",
			"the stuff", "thief", "thieving", "thorns", "thread", "three", "throne", "tick", "tile", "tiles",
			"till", "timber", "timeline", "tinderbox", "tofu", "token", "tomato", "tomb", "tombstone", "tongs",
			"tools", "top hat", "torch", "torches", "tornado", "tortoise", "totem", "tourist", "tower", "track",
			"tracks", "trader", "translation", "trap", "trapdoor", "tray", "treasure", "treaty", "tree", "tree stump",
			"trees", "trim", "troll", "trolley", "trolls", "trousers", "trumpet", "trunk", "tsunami", "tube",
			"tulips", "tunnel", "twigs", "unicorn", "valuables", "valve", "vanity", "vase", "vault", "vengeance",
			"vent", "vial", "vials", "villager", "vine", "vines", "vodka", "void", "vulnerability", "waitress",
			"wall", "wand", "warden", "wardrobe", "warrant", "warrior", "watch", "water", "waterfall", "watermelon",
			"watermelons", "wave", "weaken", "weapons", "weasel", "weather report", "weave", "weeds", "well", "werewolf",
			"werewolves", "wheat", "wheel", "wheelchair", "whirlpool", "whisky", "white", "white wolf", "wilderness", "will",
			"willow", "willow tree", "window", "wire", "wisp", "witch", "wizard", "wolf", "wolves", "woman",
			"wood", "woodcutting", "wooden table", "wool", "workbench", "worker", "workshop", "worm", "worms", "wrench",
			"yellow", "zero", "zombie", "zombies");

	private GlossaryIgnore()
	{
	}
}
