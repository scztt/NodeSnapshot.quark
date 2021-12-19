+BusScopeSynth {
	play { arg bufSize, bus, cycle, target=(RootNode(server)), addAction=\addToTail, inputUgen=In;
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
					inputUgen.ar(busIndex, busChannels),
					K2A.ar(inputUgen.kr(busIndex, busChannels))]
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
			synth = Synth(synthDef.name, synthArgs, target, addAction);
		}
	}
}
