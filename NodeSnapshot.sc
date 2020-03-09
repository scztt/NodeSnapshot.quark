NodeSnapshot {
	var <>server, <>nodeId;

	asNode {
		^Node.basicNew(server, nodeId);
	}

	== {
		this.subclassResponsibility();
		// |other|
		// ^(this.class == other.class) and: {
		// 	(nodeId == other.nodeId) and: {
		// 		if (this.class == SynthSnapshot) {
		// 			this.defName == other.defName;
		// 		}
		// 	}
		// }
	}

	embedInStream {
		this.yield;
	}

	asStream {
		^Routine({ this.embedInStream }).asStream
	}

	detect {
		|function|
		var next, stream;
		stream = this.asStream;
		while { next = stream.next; next.notNil } {
			if (function.(next)) {
				^next
			}
		};
		^nil
	}

	collect {
		|function|
		^this.asStream.collect(function).all
	}

	select {
		|function|
		^this.asStream.select(function).all;
	}

	do {
		|function|
		this.asStream.do(function)
	}

	reject {
		|function|
		^this.select({ |...args| function.(*args).not });
	}
}

GroupSnapshot : NodeSnapshot {
	var <>numChildren, <children;
	*new {
		^super.new.init()
	}

	init {
		children = List();
	}

	asGroup {
		^Group.basicNew(server, nodeId);
	}

	isSynth {
		^false
	}

	isGroup {
		^true
	}

	asString {
		arg indent = 0;
		var str, indentString = ("  " ! indent).join("");
		str = indentString ++ "+ Group: %".format(nodeId);
		^str
	}

	== {
		|other|
		^(this.class == other.class) and: {
			nodeId == other.nodeId;
		}
	}

	embedInStream {
		this.yield;

		children.do {
			|child|
			child.embedInStream();
		};
	}

}

SynthSnapshot : NodeSnapshot {
	var <defName, <desc, <controls;

	*new {
		^super.new.init()
	}

	isSynth {
		^true
	}

	isGroup {
		^false
	}

	init {
		controls = IdentityDictionary();
	}

	asSynth {
		^Synth.basicNew(defName, server, nodeId);
	}

	== {
		| other |
		// the only way we have of matching in cases where a synthdef has been changed from one update to the next is
		// matching via the def itself, not the def name
		if (this.class != other.class) {
			^false
		} {
			^(nodeId == other.nodeId) && (defName == other.defName) && (desc == other.desc);
		}
	}

	defName_{
		| inDefName |
		defName = inDefName.asSymbol;
		desc = SynthDescLib.match(defName);
		if (desc.isNil) {
			// try Ndefs
			Ndef.all.do {
				|space|
				space.do {
					|ndef|
					ndef.objects.do {
						|object|
						if (object.respondsTo(\synthDef)) {
							if (object.synthDef.name == inDefName) {
								desc = object.synthDef.asSynthDesc;
							};
						}
					};
				}
			}
		}
	}

	outputs {
		if (desc.notNil) {
			^this.prBuildInOutputs(desc.outputs);
		} {
			^[]
		}
	}

	inputs {
		if (desc.notNil) {
			^this.prBuildInOutputs(desc.inputs)
			++ this.prMappedInputs();
		} {
			^[]
		}
	}

	prBuildInOutputs {
		|coll|
		^coll.reject({
			|inout|
			inout.type.isKindOf(LocalIn.class) ||
			inout.type.isKindOf(LocalOut.class)
		}).collect({
			|inout|
			inout = inout.copy();
			if (inout.startingChannel.isKindOf(Symbol)) {
				inout.startingChannel = controls[inout.startingChannel];
			};
			inout;
		})
	}

	prMappedInputs {
		var result, mappings = Set();

		controls.keysValuesDo {
			|key, value|
			var mapped;

			if (value.isString || value.isCollection.not) {
				value = [value];
			};

			mapped = value.select({
				|v|
				(v.isSymbol || v.isString) and: { v.isMap }
			});

			if (mapped.size == value.size) {
				mappings.add(mapped.collect(_.asString));
			} {
				mappings.addAll(mapped.collect(_.asString));
			}
		};

		while { mappings.isEmpty.not } {
			var input = mappings.pop();
			var numChannels = 1;

			if (input.isString || input.isCollection.not) {
				input = [input];
			};

			if (input.collect(_[1..]).collect(_.asInteger).isSeries(1)) {
				numChannels = input.size;
				input = input[0];
			};

			if (input.isString) {
				result = result.add(
					IODesc()
					.rate_((input[0] == $a).if(\audio, \control))
					.numberOfChannels_(numChannels)
					.type_((input[0] == $a).if(In, InFeedback))
					.startingChannel_(input[1..].asInteger)
				)
			} {
				// if it's still an array here, just process separately
				mappings = mappings.addAll(input);
			}
		};

		^result
	}

	outBusses {
		^this.outputs.collect {
			|output|
			Bus(output.rate, output.startingChannel, output.numberOfChannels, server);
		}
	}

	controlsString {
		|join=" ", dec = 0.01|
		^controls.asKeyValuePairs.clump(2).collect({
			|pair|
			"%: %".format(pair[0], pair[1].round(dec))
		}).join(join);
	}

	asString {
		arg indent = 0;
		var str, indentString = (("  " ! indent).join(""));
		str = indentString ++ "+ Synth(%): %s".format(nodeId, defName);
		^str
	}
}

TraceParser {
	var server, msg, index, hasControls, <rootNode, <nodes;

	*parse {
		|server, msg|
		^super.newCopyArgs(server, msg).parse();
	}

	parse {
		//[ /g_queryTree.reply, 1, 0, 2, 1, 0, 1000, -1, volumeAmpControl2, 4, volumeAmp, 0.2786121070385, volumeLag, 0.10000000149012, gate, 1, bus, 0 ]
		index = 1;
		nodes = List();
		hasControls = this.next() == 1;
		rootNode = this.parseNode();
	}

	parseNode {
		var snapshot, nodeId, numChildren;
		nodeId = this.next();
		numChildren = this.next();
		if (numChildren < 0) {
			snapshot = SynthSnapshot().server_(server).nodeId_(nodeId);
			nodes.add(snapshot);
			this.parseSynth(snapshot);
			^snapshot;
		} {
			snapshot = GroupSnapshot().server_(server).nodeId_(nodeId).numChildren_(numChildren);
			nodes.add(snapshot);
			this.parseGroup(snapshot);
			^snapshot;
		}
	}

	parseGroup {
		arg snapshot;
		snapshot.numChildren.do {
			var child = this.parseNode();
			snapshot.children.add(child);
		}
	}

	parseSynth {
		arg snapshot;
		var controlCount, lastControl;
		snapshot.defName = this.next();
		if (hasControls) {
			controlCount = this.next();
			controlCount.do {
				var name, val;
				name = this.next(); val = this.next();
				if (name.isKindOf(Symbol)) {
					lastControl = name;
					snapshot.controls[name] = val;
				} {
					snapshot.controls[lastControl] = snapshot.controls[lastControl].asArray.add(val);
				};
			}
		}
	}

	next {
		arg items = 1;
		var nextMsgItem;
		if (items == 1) {
			nextMsgItem = msg[index];

		} {
			nextMsgItem = msg[index..(index + items - 1)];
		};
		index = index + items;
		^nextMsgItem
	}
}

TreeSnapshot {
	classvar <>dump=false;
	var <server, msg, <nodes, <root, <drawFunc;

	*get {
		arg action, node, ignore=[];
		var server;
		node = node ?? { RootNode(Server.default) };
		server = node.server;

		OSCFunc({
			arg msg;
			var snapshot, parsed;
			if (dump) { msg.postln };
			snapshot = TreeSnapshot(server, msg);
			{ action.value(snapshot) }.defer;
		}, '/g_queryTree.reply').oneShot;

		server.sendMsg("/g_queryTree", node.nodeID, 1);
	}

	*new {
		arg server, traceMsg;
		^super.newCopyArgs(server, traceMsg).parse()
	}

	nodeIDs {
		^nodes.collect(_.nodeId);
	}

	parse {
		var parsed;
		parsed = TraceParser.parse(server, msg);
		nodes = parsed.nodes;
		root = parsed.rootNode;
	}

	asString {
		^"TreeSnapshot\n" ++ root.asString(1);
	}

	storeOn {
		| stream, indent=0 |
		stream << "TreeSnapshot: " ++ this.asString();
	}

	nodeOrder {
		^root.asStream.all
	}
}

TreeSnapshotView : Singleton {
	classvar okayIcon, warningIcon, groupColor, groupOutline, font;
	var <view, <viewMap, <viewsInUse, currentSnapshot, collapse=false,
	autoUpdateRoutine, autoUpdate=true, <ignore, lastValidation;

	*initClass {
		groupColor = Color.hsv(0.35, 0.6, 0.5, 0.5);
		groupOutline = Color.grey(1, 0.3);
		okayIcon = Material("check_box", 16, color:Color.hsv(0.35, 0.8, 0.8, 0.5));
		warningIcon = Material("warning", 16, color:Color.hsv(0.0, 0.8, 0.8, 0.5));
		font = Font("M+ 1c", 10, false).freeze;
	}

	init {
		viewMap = IdentityDictionary();
		viewsInUse = IdentitySet();
		this.ignore = [".*stethoscope.*", ".*BusStatsUpdater.*"];
	}

	front {
		if (view.isNil) {
			this.makeNew();
		} {
			view.front;
		};
	}

	ignore_{
		|inIgnore|

		ignore = inIgnore.collect {
			|ignore|
			if (ignore.isKindOf(String)) {
				var ignoreStr = ignore;
				ignore = {
					|defName|
					ignoreStr.matchRegexp(defName.asString)
				}
			};
			ignore;
		};

		if (autoUpdateRoutine.notNil) {
			autoUpdateRoutine.updateFunc.();
		} {
			TreeSnapshot.get({
				|sn|
				this.update(sn);
			}, RootNode(currentSnapshot !? { currentSnapshot.server } ?? Server.default));
		}
	}

	autoUpdate {
		|up=true, server|
		autoUpdate = up;
		if (up) {
			if (autoUpdateRoutine.notNil) { autoUpdateRoutine.stop() };
			autoUpdateRoutine = SkipJack({
				TreeSnapshot.get({
					|sn|
					this.update(sn);
				}, RootNode(server ?? Server.default));
			}, 0.5);
		} {
			if (up.not && autoUpdateRoutine.notNil) {
				autoUpdateRoutine.stop;
				autoUpdateRoutine = nil;
			}
		}
	}

	update {
		|newSnapshot|
		var oldViews;

		if (view.notNil) {
			oldViews = viewsInUse;
			viewsInUse = IdentitySet();
			currentSnapshot = newSnapshot;
			this.makeViewNode(currentSnapshot.root);
			oldViews.difference(viewsInUse).do {
				|toRemove|
				toRemove.view.visible = false;
				//{ toRemove.view.remove() }.defer(0.1);
				if (viewMap[toRemove.snapshot.nodeId] == toRemove) {
					viewMap[toRemove.snapshot.nodeId] = nil;
				}
			}
		}
	}

	makeNew {
		TreeSnapshot.get({
			|sn|
			{
				var wrapperView;
				currentSnapshot = sn;

				wrapperView = View().layout_(VLayout(
					this.makeHeader(),
					view = (ScrollView()
						.canvas_(View().layout_(VLayout(
							this.makeViewNode(sn.root),
							nil
						).margins_(0).spacing_(0)))
					)
				).spacing_(0).margins_(8));
				view.background = QtGUI.palette.window;

				view.onClose = {
					this.autoUpdate(false);
					view = nil;
					viewsInUse.clear();
					viewMap.clear();
				};

				wrapperView.front;
				wrapperView.autoRememberPosition(\TreeSnapshotView, name);

				this.autoUpdate(autoUpdate);
			}.defer
		})
	}

	makeHeader {
		var button, view, menu, bar, doValidation;

		doValidation = {
			lastValidation = TreeValidator.validate(currentSnapshot.root);

			if (lastValidation.isEmpty) {
				button.icon = okayIcon;
			} {
				button.icon = warningIcon;
			};
			lastValidation;
		};

		view = View().maxHeight_(32).layout_(HLayout(
			nil,
			bar = ToolBar(
				menu = (MenuAction()).asMenu
			).toolButtonStyle_(QToolButtonStyle.textBesideIcon)
		).margins_(0).spacing_(0));

		button = bar.actions[0];
		button.icon = okayIcon;
		button.string = "validate";
		button.action = doValidation;

		menu.signal(\aboutToShow).connectToUnique({
			var warnings;

			menu.clear();

			if (currentSnapshot.notNil) {
				lastValidation = lastValidation ?? doValidation;
				if (lastValidation.isEmpty) {
					menu.addAction(MenuAction("No problems detected."))
				} {
					lastValidation.do {
						|warning|
						menu.addAction(
							MenuAction(warning)
							.font_(font)
						)
					}
				}
			}
		});

		^view;
	}

	makeViewNode {
		| node ...args |
		var viewObj = viewMap[node.nodeId];
		if (viewObj.notNil) {
			if (viewObj.snapshot == node) {
				viewObj.snapshot = node;
			} {
				viewObj = nil;
			}
		};

		case
		{ node.isKindOf(GroupSnapshot) } {
			viewObj = viewObj ?? { this.makeViewGroup(node, *args) };
			this.populateViewGroup(viewObj);
		}
		{ node.isKindOf(SynthSnapshot) } {
			if (ignore.detect({ |f| f.value(node.defName) }).isNil) {
				viewObj = viewObj ?? { this.makeViewSynth(node, *args) };
			}
		};

		// { node.isKindOf(Collection) } {
		// 	if ((node.size() > 1) && collapse) {
		// 		^this.makeViewCollapsedSynth(node, *args);
		// 	} {
		// 		^this.makeViewSynth(node, *args);
		// 	}
		// }
		if (viewObj.notNil) {
			viewObj.set(node);
			viewsInUse.add(viewObj);
			viewMap[node.nodeId] = viewObj;
			^viewObj.view;
		} {
			^nil;
		}
	}

	drawBorder {
		|v, color|
		Pen.addRect(v.bounds.moveTo(0, 0).insetBy(0.5, 0.5));
		Pen.strokeColor = color;
		Pen.strokeRect();
	}

	separateSynths {

	}

	makeViewGroup {
		| group |
		var gsv = GroupSnapshotView(group);
		gsv.view = (UserView()
			.background_(groupColor)
			.drawFunc_(this.drawBorder(_, groupOutline))
		);

		gsv.view.layout = VLayout().spacing_(3).margins_(5);
		gsv.view.layout.add(
			StaticText().font_(font.copy.bold_(true))
			.fixedHeight_(26)
			.string_("[%] group".format(group.nodeId))
			.mouseUpAction_({
				|v|
				gsv.folded = gsv.folded.not;
				gsv.childrenView.visible = gsv.folded.not;
			})
		);
		gsv.childrenView = View();
		gsv.childrenView.layout = VLayout().spacing_(0).margins_(0);
		gsv.view.layout.add(gsv.childrenView);
		//gsv.view.layout.add(nil);

		// if (collapse) {
		// 	(group.children
		// 		.separate(this.separateSynthDefs(_))
		// 		.collect(this.makeViewNode(_))
		// 	)
		// } {
		// 	group.children.collect(this.makeViewNode(_)).do {
		// 		|v|
		// 		gsv.view.layout.add(v);
		// 	};
		// };

		^gsv
	}

	populateViewGroup {
		| gsv |
		if (collapse) {
			(gsv.snapshot.children
				.separate(this.separateSynthDefs(_))
				.collect(this.makeViewNode(_))
			)
		} {
			var views, layout;
			layout = VLayout().margins_(0).spacing_(3);
			views = gsv.snapshot.children.collect(this.makeViewNode(_));
			views = views.reject(_.isNil);
			views.do {
				|v, i|
				layout.insert(v, i + 1);
			};
			//layout.insert(nil, gsv.snapshot.children.size);
			gsv.childrenView.layout = layout;
		};
	}

	makeViewSynth {
		|synth|
		var sv = SynthSnapshotView(synth).font_(font);
		sv.view = UserView().layout_(
			VLayout().spacing_(0).margins_(2)
		);

		sv.view.layout.add(this.makeSynthHeader(sv));
		sv.view.layout.add(2);
		sv.view.layout.add(this.makeSynthBody(sv));

		(sv.view.background_(Color.grey(0.2, 0.8))
			.minHeight_(20)
			.drawFunc_(this.drawBorder(_, Color.grey(1, 0.3)))
		);

		^sv
	}

	makeSynthHeader {
		|sv|
		^HLayout(
			(StaticText()
				.string_("[%]".format(sv.synth.nodeId))
				.stringColor_(QtGUI.palette.highlightText)
				.font_(font)
			),
			10,
			(StaticText()
				.string_("\\" ++ sv.synth.defName)
				.stringColor_(QtGUI.palette.highlightText)
				.font_(font.copy.bold_(true))
			),
			nil,
			[StaticText()
				.string_("TRACE")
				.align_(\right)
				.font_(font.copy.size_(7.5).bold_(true))
				.mouseDownAction_({
					sv.synth.asSynth.trace();
				})
				, align:\topRight
			],
			8,
			[StaticText()
				.string_("✕")
				.align_(\right)
				.font_(font.copy.size_(8).bold_(true))
				.mouseDownAction_({
					sv.synth.asSynth.free();
					TreeSnapshot.get({ |sn| this.update(sn); })
				})
				, align:\topRight
			]
		)
	}

	makeSynthBody {
		|sv|
		var bodyView, ioView;
		bodyView = HLayout(
			VLayout(
				this.makeSynthControls(sv),
				nil
			).spacing_(0).margins_(0),
			ioView = View().layout_(VLayout()
				.margins_(0)
				.spacing_(0)
			),
			nil
		);
		sv.ioView = ioView;
		^bodyView
	}

	makeSynthControl {
		| sv, controlName, value |
		var view, valueField;
		view = View().minWidth_(50);
		view.layout = HLayout().spacing_(0).margins_(1);
		view.layout.add(nil);
		view.layout.add(
			StaticText()
			.maxHeight_(10)
			.string_(controlName.asString.toUpper)
			.font_(font.copy.size_(8))
			.stringColor_(QtGUI.palette.windowText),
			align: \bottomRight
		);

		sv.controls[controlName] = valueField = (TextField()
			.palette_(QPalette().window_(Color.clear).base_(Color.clear))
			.maxHeight_(14)
			.minWidth_(230)
			.maxWidth_(230)
			.stringColor_(QtGUI.palette.highlightText)
			.font_(font.copy.size_(10).bold_(true))
		);

		view.layout.add(valueField, align: \bottomRight);

		^[view, align:\right]
	}

	/*	makeSynthControl {
	| sv, controlName, value |
	var view, nameField, valueField;
	// view = View().minWidth_(50);
	// view.layout = HLayout().spacing_(0).margins_(1);

	nameField = (StaticText()
	.maxHeight_(10)
	.string_(controlName.asString.toUpper)
	.align_(\right)
	.font_(Font("M+ 1c", 8, false))
	.stringColor_(QtGUI.palette.windowText)
	);

	sv.controls[controlName] = valueField = (TextField()
	.palette_(QPalette().window_(Color.clear).base_(Color.clear))
	.maxHeight_(14)
	.align_(\left)
	.stringColor_(QtGUI.palette.highlightText)
	.font_(Font("M+ 1c", 10, true))
	);

	// view.layout.add(valueField, align: \bottomRight);
	// view.layout.add(nil);

	^[ [nameField, align: \bottomRight], [valueField, align: \bottomLeft] ];
	}*/

	makeSynthControls {
		|sv, cols=6|
		var layout, controls, controlViews;
		controls = sv.synth.controls.asKeyValuePairs.clump(2);
		controlViews = controls.collect({
			|keyval|
			this.makeSynthControl(sv, *keyval)
		});
		controlViews = controlViews ++ (nil ! (cols - (controlViews.size % cols)));

		^GridLayout.rows(
			*(controlViews.clump(cols))
		).spacing_(1);
	}
}

GroupSnapshotView {
	var <>snapshot, <>view, <>childrenView, <>folded = false;

	*new {
		|groupSnapshot, view|
		^super.newCopyArgs(groupSnapshot, view);
	}

	childLayout_{
		view.layout
	}

	set {}
}

SynthSnapshotView {
	var <>snapshot, <>view, <>controls, <>ioView, <>inOuts,
	<>font;

	*new {
		|synthSnapshot, view|
		^super.newCopyArgs(synthSnapshot, view, IdentityDictionary());
	}

	synth { ^snapshot }

	set {
		|newSynth|
		var toRemove, toAdd, newIO;

		snapshot = newSynth;

		this.makeSynthInOutputs(snapshot, ioView);

		snapshot.controls.keysValuesDo {
			|controlName, value|
			var view = controls[controlName];
			if (view.isNil) {
				"View for control '%' of synth % was nil!".format(controlName, snapshot.nodeId).warn;
			} {
				if (view !? { |v| v.hasFocus.not } ?? false) {
					view.string_("%".format(value.round(0.001)))
					.action_({
						|v|
						var node, val;
						val = v.value;
						if ((val[0] == $c) || (val[0] == $a)) {
							snapshot.asSynth.map(controlName.asSymbol, val[1..].asInteger)
						} {
							val = val.interpret;
							if (val.notNil) {
								snapshot.asSynth.setn(controlName.asSymbol, val);
							}
						};

						{ v.background = Color.clear }.defer(0.1);
						v.background_(v.background.blend(Color.green(1, 0.1)));
						v.focus(false);
					});
				}
			};
		}
	}

	makeSynthInOutput {
		|synth, output, inOut|
		var type, bus, addAction, view, inoutCharacters, channelCharacters;

		inoutCharacters = (
			in: (
				audio: "◀",
				control: "◁"
			),
			out: (
				audio: "▶",
				control: "▷"
			)
		);
		addAction = (in: \addBefore, out: \addAfter)[inOut];
		channelCharacters = ["⠂", "⠆", "⠴", "⠶", "⠾", "⠿"];

		type = inoutCharacters[inOut][output.rate];
		type = type ++ channelCharacters.clipAt(output.numberOfChannels-1);

		bus = output.startingChannel;
		if (bus.isNumber.not) {
			bus = "∗"
		} {
			bus = bus.asInteger
		};

		view = (StaticText()
			.align_(\right)
			.canFocus_(false)
			.font_(font.copy.size_(9))
			.string_("% %".format(bus, type))
			.stringColor_(QtGUI.palette.highlightText)
			.background_(Color.grey(0.5, 0.1))
			.maxHeight_(12)
			.minWidth_(30)
		);
		view.bounds = view.bounds.size_(view.sizeHint);
		view.attachHoverScope(output, synth.asNode, addAction, size:300@80, align:\right);

		^view
	}

	makeSynthInOutputs {
		|synth, parent|
		var oldInOuts, oldView, newView;
		var layout = VLayout().spacing_(1).margins_(0);
		var sortFunc = {
			|a, b|
			a.startingChannel < b.startingChannel
		};

		oldInOuts = inOuts ?? { Dictionary() };
		inOuts = Dictionary();

		synth.inputs.sort(sortFunc).do {
			|input|
			newView = oldInOuts[input] ?? {
				this.makeSynthInOutput(synth, input, \in)
			};
			inOuts[input] = newView;
			oldInOuts[input] = nil;
			layout.add(newView);
		};

		synth.outputs.sort(sortFunc).do {
			|output|
			newView = oldInOuts[output] ?? {
				this.makeSynthInOutput(synth, output, \out)
			};
			inOuts[output] = newView;
			oldInOuts[output] = nil;
			layout.add(newView);
		};

		oldInOuts.values.do(_.remove);

		parent.layout = layout;
	}
}

SynthOutputDisplay : View {
	var synth, minMaxSynth, scopeView, buffer;
	var <>window;

	*qtClass { ^'QcDefaultWidget' }

	*newWindow { arg parent, bounds, bus, inputType, node, addAction;
		var wind, self;

		wind = Window(
			"Synth output: %[%]".format(bus.index, bus.numChannels),
			bounds:bounds ?? {this.sizeHint},
			border:false
		);

		self = this.new(wind, bounds.copy.moveTo(0,0), bus, inputType, node, addAction);
		self.window = wind;
		wind.front;

		^self
	}

	*new {
		|parent, bounds, bus, inputType, node, addAction|
		^super.new(parent, bounds).init(bus, inputType, node, addAction);
	}

	prMakeLabel {
		|name|
		^StaticText()
		.canFocus_(false)
		.font_(Font("M+ 1c", 10, true))
		.string_(name ++ ":")
		.maxWidth_(40)
		.align_(\right)
	}

	prMakeNumberBox {
		^NumberBox()
			.canFocus_(false)
			.font_(Font("M+ 1c", 10, true))
			.maxWidth_(40)
			.minDecimals_(1)
			.maxDecimals_(3)
	}

	init {
		|bus, inputType, node, addAction|
		var parent, minView, maxView, avgView, baseString, min=99999, max=(-99999);

		addAction = addAction ?? \addAfter;

		this.canFocus = false;
		this.alwaysOnTop = true;

		synth = BusScopeSynth(bus.server);
		synth.play(2048, bus, 2048, node, addAction);
		if (synth.bufferIndex == 0) {
			synth = BusScopeSynth(bus.server);
			synth.play(2048, bus, 2048, node, addAction);
		};

		minMaxSynth = SignalStatsUpdater(
			inputType.switch,
			SignalStatsUpdater.combinedMinMaxAvgFunc,
			target: node,
			rate: 1,
			addAction: addAction
		);
		baseString = "Bus % [% %ch]\n=> %".format(
			bus.index.asInteger,
			bus.rate.switch(\control, \kr, \audio, \ar),
			bus.numChannels.asInteger,
			inputType
		);

		minMaxSynth.signal(\value).connectToUnique({
			|who, what, avg, newMin, newMax|
			[avg, newMin, newMax].postln;
			max = max.max(newMax);
			min = min.min(newMin);
			minView.value = min;
			maxView.value = max;
			avgView.value = avg;
		}).defer;

		this.layout_(VLayout(
			HLayout(
				StaticText()
					.canFocus_(false)
					.font_(Font("M+ 1c", 10, false))
					.string_(baseString),
				this.prMakeLabel("avg"),
				avgView = this.prMakeNumberBox(),
				this.prMakeLabel("min"),
				minView = this.prMakeNumberBox(),
				this.prMakeLabel("max"),
				maxView = this.prMakeNumberBox(),
			).spacing_(2).margins_(0),
			scopeView = ScopeView(bounds:Rect(0, 0, 130, 130));
		).margins_(1).spacing_(0));

		(scopeView
			.bufnum_(synth.bufferIndex)
			.server_(bus.server)
			.fill_(bus.rate == \audio)
			.waveColors_(bus.numChannels.collect {
				|i|
				var h, s, v, a;
				#h, s, v, a = Color.hsv(0.555, 1, 0.6).asHSV();
				h = (h + (i * 0.68)).mod(1).min(1).max(0);
				Color.hsv(h, s, v, a);
			})
			.style_(1));

		fork({
			bus.server.sync;
			scopeView.start;
		}, AppClock);

		if (this.parent.notNil) {
			parent = this.parents.last;
		} {
			parent = this;
		};

		parent.onClose = {
			synth.free;
			minMaxSynth.free;
			scopeView.stop;
		}
	}

	autoClose_{
		|b|
		if (b) {
			this.mouseUpAction = {
				this.close();
			};
			this.mouseLeaveAction = {
				this.close();
			};
		} {
			this.mouseUpAction = nil;
			this.mouseLeaveAction = nil;
		}
	}

	close {
		if (window.isNil) {
			super.close();
		} {
			window.close();
		}
	}
}

TreeValidator {
	classvar <>validationPasses;

	*initClass {
		this.addValidation({
			|root|
			this.prValidateNodeOrder(root)
		})
	}

	*addValidation {
		|function|
		validationPasses = validationPasses.add(function)
	}

	*validate {
		|root|
		var warnings;
		validationPasses.do {
			|pass|
			pass.(root);
			warnings = warnings.addAll();
		};
		^warnings;
	}

	*prValidateNodeOrder {
		|root|
		var readsFrom, writesTo, feedsFrom, warnings;

		readsFrom = Dictionary();
		writesTo = Dictionary();
		feedsFrom = Dictionary();
		warnings = Set();

		root.do({
			|node|

			if (node.isSynth) {

				// INPUTS
				node.inputs.do {
					|input|
					input.numberOfChannels.do {
						|i|
						i = [input.rate, asInteger(input.startingChannel + i)];

						if (writesTo[i].isNil) {
							if ((input.type == InFeedback)
								|| (input.rate == \control)) {
								feedsFrom[i] = node;
							} {
								warnings = warnings.add(
									"% is reading from % before it was written to.".format(
										node,
										Bus(i[0], i[1], 1)
									)
								)
							}
						};

						readsFrom[i] = readsFrom[i].add(node);
					}
				};

				// OUTPUTS
				node.outputs.do {
					|output|
					output.numberOfChannels.do {
						|i|
						i = [output.rate, asInteger(output.startingChannel + i)];

						if ((output.type == ReplaceOut) && writesTo[i].notNil && (readsFrom[i].isNil)) {
							warnings = warnings.add(
								"% is overwriting % before it was read (previously written by %)".format(
									node,
									Bus(i[0], i[1], 1),
									writesTo[i],
								)
							)
						};

						readsFrom[i] = nil;
						writesTo[i] = node;
					}
				};
			}
		});

		^warnings
	}
}

+SynthDesc {
	hash {
		^this.instVarHash(#[\name, \inputs, \outputs, \controlDict, \constants, \metadata])
	}

	== {
		|other|
		^(other.class == this.class) and: {
			(name == other.name)
			&& (inputs == other.inputs)
			&& (outputs == other.outputs)
			&& (controlDict == other.controlDict)
			&& (constants == other.constants)
			&& (metadata == other.metadata)
		}
	}
}

+IODesc {
	hash {
		^this.instVarHash(#[\rate, \numberOfChannels, \startingChannel, \type])
	}

	== {
		|other|
		^(other.class == this.class) and: {
			(rate == other.rate)
			&& (numberOfChannels == other.numberOfChannels)
			&& (startingChannel == other.startingChannel)
			&& (type == other.type)
		}
	}
}

+ControlName {
	hash {
		^this.instVarHash(#[\name, \index, \rate, \defaultValue, \argNum, \lag])
	}

	== {
		|other|
		^(other.class == this.class) and: {
			(name == other.name)
			&& (index == other.index)
			&& (rate == other.rate)
			&& (defaultValue == other.defaultValue)
			&& (argNum == other.argNum)
			&& (lag == other.lag)
		}
	}
}

+View {
	attachHoverScope {
		|output, target, addAction, size, align=\left|

		this.mouseDownAction = {
			|v, x, y|
			var origin, winBounds, disp, bus, actualSize;
			var focused;
			var rate, index, numChannels, type='';

			target = target.asTarget;

			actualSize = size ?? (this.bounds.width @ 80);

			if (output.isKindOf(IODesc)) {
				bus = Bus(output.rate, output.startingChannel, output.numberOfChannels, target.server);
				type = output.type;
			} {
				bus = output.value;
			};

			focused = QtGUI.focusView;

			origin = switch(align,
				\left, 0@0,
				\right, v.bounds.width@0
			);

			origin = v.mapToGlobal(origin);

			disp = SynthOutputDisplay.newWindow(
				bounds:Rect(
					origin.x,
					Window.screenBounds.height - (origin.y + actualSize.y),
					// origin.y - 10,
					actualSize.x,
					actualSize.y
				),
				bus: bus,
				inputType: type,
				node: target,
				addAction: addAction
			).autoClose_(true);

			this.mouseUpAction = {
				disp.close;
				this.mouseUpAction = nil;
			};

			disp.alpha = 0.75;
			disp.visible = true;
			focused !? _.focus;
			this.focus
		};
	}
}

+BusScopeSynth {
	play { arg bufSize, bus, cycle, target, addAction;
		var synthDef;
		var synthArgs;
		var bufIndex;
		var busChannels;

		if(server.serverRunning.not) { ^this };

		this.stop;

		if (buffer.isNil) {
			buffer = ScopeBuffer.alloc(server);
			synthDefName = "stethoscope" ++ buffer.index.asString;
		};

		bufIndex = buffer.index.asInteger;

		if( bus.class === Bus ) {
			busChannels = bus.numChannels.asInteger;
			synthDef = SynthDef(synthDefName, { arg busIndex, rate, cycle;
				var z;
				z = Select.ar(rate, [
					In.ar(busIndex, busChannels),
					K2A.ar(In.kr(busIndex, busChannels))]
				);
				ScopeOut2.ar(z, bufIndex, bufSize, cycle );
			});
			synthArgs = [\busIndex, bus.index.asInteger, \rate, if('audio' === bus.rate, 0, 1), \cycle, cycle];
		}{
			synthDef = SynthDef(synthDefName, { arg cycle;
				var z = Array();
				bus.do { |b| z = z ++ b.ar };
				ScopeOut2.ar(z, bufIndex, bufSize, cycle);
			});
			synthArgs =	[\cycle, cycle];
		};

		playThread = fork {
			synthDef.send(server);
			server.sync;
			synth = Synth(synthDef.name, synthArgs, target.asTarget, addAction ?? \addAfter);
		}
	}
}
