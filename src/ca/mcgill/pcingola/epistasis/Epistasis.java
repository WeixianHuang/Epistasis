package ca.mcgill.pcingola.epistasis;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.apache.commons.math3.linear.RealVector;
import org.apache.commons.math3.util.Pair;

import ca.mcgill.mcb.pcingola.snpEffect.commandLine.CommandLine;
import ca.mcgill.mcb.pcingola.stats.CountByType;
import ca.mcgill.mcb.pcingola.stats.Counter;
import ca.mcgill.mcb.pcingola.util.Gpr;
import ca.mcgill.mcb.pcingola.util.GprSeq;
import ca.mcgill.mcb.pcingola.util.Timer;
import ca.mcgill.pcingola.epistasis.entropy.EntropySeq;
import ca.mcgill.pcingola.epistasis.entropy.EntropySeq.InformationFunction;
import ca.mcgill.pcingola.epistasis.phylotree.EstimateTransitionMatrix;
import ca.mcgill.pcingola.epistasis.phylotree.EstimateTransitionMatrixPairs;
import ca.mcgill.pcingola.epistasis.phylotree.LikelihoodTree;
import ca.mcgill.pcingola.epistasis.phylotree.TransitionMatrix;

/**
 * Main command line
 *
 * @author pcingola
 */
public class Epistasis implements CommandLine {

	public static int MIN_DISTANCE = 1000000;
	public static boolean debug = false;

	boolean nextProt;

	boolean filterMsaByIdMap = true;
	String[] args;
	String cmd;
	String aaContactFile, configFile, genome, idMapFile, multAlignFile, pdbDir, qMatrixFile, treeFile;
	LikelihoodTree tree;
	DistanceResults aaContacts;
	TransitionMatrix Q, Q2;
	MultipleSequenceAlignmentSet msas;
	EstimateTransitionMatrix mltm;
	IdMapper idMapper;
	PdbGenome pdbGenome;

	public static void main(String[] args) {
		Epistasis epistasis = new Epistasis(args);
		epistasis.run();
	}

	public Epistasis(String[] args) {
		this.args = args;
	}

	@Override
	public String[] getArgs() {
		return args;
	}

	/**
	 * Is this sequence fully conserved?
	 */
	boolean isFullyConserved(String seq) {
		char prevBase = '-';
		for (int i = 0; i < seq.length(); i++) {
			char base = seq.charAt(i);
			if (base == '-') continue;
			if (prevBase != '-' && prevBase != base) return false;
			prevBase = base;
		}
		return true;
	}

	/**
	 * Are all sequences fully conserved?
	 */
	boolean isFullyConserved(String[] seqs) {
		for (int i = 0; i < seqs.length; i++)
			if (seqs[i] != null && !isFullyConserved(seqs[i])) return false;
		return true;
	}

	/**
	 * Load files
	 */
	void load() {
		if (idMapFile != null) loadIdMap(idMapFile);
		if (treeFile != null) loadTree(treeFile);
		if (multAlignFile != null) {
			loadMsas(multAlignFile);
			sanityCheck(tree, msas); // Sanity check: Make sure that the alignment and the tree match
		}
		if (aaContactFile != null) loadAaContact(aaContactFile);

		if (genome != null) {
			pdbGenome = new PdbGenome(configFile, genome, pdbDir);
			pdbGenome.setDebug(debug);
			pdbGenome.setIdMapper(idMapper);
			pdbGenome.setMsas(msas);
			pdbGenome.setTree(tree);
			pdbGenome.setNextProt(nextProt);
			pdbGenome.initialize();
		}

		if (qMatrixFile != null) loadQ(qMatrixFile);
	}

	/**
	 * Load AA contact list
	 */
	List<DistanceResult> loadAaContact(String aaContactFile) {
		Timer.showStdErr("Loading AA contact information " + aaContactFile);
		aaContacts = new DistanceResults();
		aaContacts.load(aaContactFile);
		return aaContacts;
	}

	void loadIdMap(String idMapFile) {
		Timer.showStdErr("Loading id maps " + idMapFile);
		idMapper = new IdMapper(idMapFile);
	}

	/**
	 * Load MSAs
	 */
	void loadMsas(String multAlign) {
		int numAligns = tree.childNames().size();

		// Load: MSA
		Timer.showStdErr("Loading " + numAligns + " way multiple alignment from " + multAlign);
		msas = new MultipleSequenceAlignmentSet(multAlign, numAligns);
		msas.load();

		// Filter by idMap?
		if (filterMsaByIdMap) {
			MultipleSequenceAlignmentSet msasNew = new MultipleSequenceAlignmentSet(multAlign, numAligns);
			msasNew.setSpecies(msas.getSpecies());

			msas.stream() //
					.filter(m -> idMapper.getByRefSeqId(m.transcriptId) != null) //
					.forEach(m -> msasNew.add(m));

			// Replace with filtered version
			Timer.showStdErr("Done. Filtered MSAS by IdMap. Number of entries before: " + msas.size() + ", after: " + msasNew.size());
			msas = msasNew;
		} else Timer.showStdErr("Done. Total number of alignments: " + msas.size());
	}

	/**
	 * Load Q matrix
	 */
	void loadQ(String qMatrixFile) {
		Timer.showStdErr("Loading Q matrix  from file '" + qMatrixFile);
		if (!Gpr.exists(qMatrixFile)) {
			System.err.println("Matrix file doesn't exists, nothing done");
			return;
		}

		EstimateTransitionMatrix mltm = new EstimateTransitionMatrix(tree, msas);
		Q = mltm.loadTransitionMatrix(qMatrixFile);
	}

	/**
	 * Load a tree from a file
	 */
	void loadTree(String phyloFileName) {
		Timer.showStdErr("Loading phylogenetic tree from " + phyloFileName);
		tree = new LikelihoodTree();
		tree.load(phyloFileName);
	}

	/**
	 * Parse command line arguments
	 */
	@Override
	public void parseArgs(String[] args) {
		if (args.length < 1) usage("Missing command");
		cmd = args[0];
		Timer.showStdErr("Start command: '" + cmd + "'");

		int argNum = 1, numBases, numSamples;
		String type = "";

		switch (cmd.toLowerCase()) {
		case "aacontactstats":
			type = args[argNum++];
			aaContactFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runAaContactStats(type);
			break;

		case "aacontactstatsn":
			type = args[argNum++];
			numBases = Gpr.parseIntSafe(args[argNum++]);
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			if (numBases <= 0) usage("Number of bases must be positive number");
			filterMsaByIdMap = true;
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runAaContactStatsN(type, numBases);
			break;

		case "aafilteridmap":
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runAaFilterIdMap();
			break;

		case "aafreqs":
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			filterMsaByIdMap = true;
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runAaFrequencies();
			break;

		case "addmsaseqs":
			configFile = args[argNum++];
			genome = args[argNum++];
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			filterMsaByIdMap = false;
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runAddMsaSeqs();
			break;

		case "background":
			type = args[argNum++];
			numBases = Gpr.parseIntSafe(args[argNum++]);
			numSamples = Gpr.parseIntSafe(args[argNum++]);
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			filterMsaByIdMap = true;
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			if (numBases < 0) usage("Number of bases must be non-negative number");
			if (numSamples <= 0) usage("Number of samples must be positive number");
			runBackground(type, numBases, numSamples);
			break;

		case "conservation":
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			filterMsaByIdMap = true;
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runConservation();
			break;

		case "mappdbgenome":
			configFile = args[argNum++];
			genome = args[argNum++];
			pdbDir = args[argNum++];
			idMapFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runMapPdbGene();
			break;

		case "mappdbgenomebest":
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runMapPdbGeneBest();
			break;

		case "nextprot":
			configFile = args[argNum++];
			genome = args[argNum++];
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runNextProt();
			break;

		case "pdbdist":
			// Parse command line
			double distThreshold = Gpr.parseDoubleSafe(args[argNum++]);
			if (distThreshold <= 0) usage("Distance must be a positive number: '" + args[argNum - 1] + "'");
			int aaMinSeparation = Gpr.parseIntSafe(args[argNum++]);
			pdbDir = args[argNum++];
			idMapFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runPdbDist(distThreshold, aaMinSeparation);
			break;

		case "qhat":
			if (args.length < 4) usage("Missing arguments for command '" + cmd + "'");
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			filterMsaByIdMap = true;
			runQhat();
			break;

		case "qhat2":
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			filterMsaByIdMap = true;
			runQhat2();
			break;

		case "transitions":
			numSamples = Gpr.parseIntSafe(args[argNum++]);
			treeFile = args[argNum++];
			multAlignFile = args[argNum++];
			idMapFile = args[argNum++];
			aaContactFile = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			filterMsaByIdMap = true;
			runTransitions(numSamples);
			break;

		case "test":
			String fileName = args[argNum++];
			if (args.length != argNum) usage("Unused parameter/s for command '" + cmd + "'");
			runTest(fileName);
			break;

		default:
			throw new RuntimeException("Unknown command: '" + cmd + "'");
		}

		Timer.showStdErr("Done command: '" + cmd + "'");
	}

	/**
	 * Parse and Dispatch to right command
	 */
	@Override
	public boolean run() {
		parseArgs(args);
		return true;
	}

	/**
	 * Calculate MI and other statistics
	 */
	void runAaContactStats(String type) {
		// Select which function to use
		Function<DistanceResult, Double> f = selectFunction(type);
		load();

		//---
		// Group by genomic position
		//---
		Timer.showStdErr("Sort by position");
		DistanceResults aaContactsUniq = new DistanceResults();
		aaContacts.stream() //
				.filter(d -> !d.aaSeq1.isEmpty() && !d.aaSeq2.isEmpty()) // Filter out empty sequences
				.forEach(d -> aaContactsUniq.collectMin(d, d.toStringPos()));
		aaContactsUniq.addMins(); // Move 'best' results from hash to list

		//---
		// Show MI and conservation
		//---
		aaContactsUniq.stream() //
				.forEach( //
						d -> System.out.printf("%s\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\t%.6e\n" //
								, d //
								, EntropySeq.mutualInformation(d.aaSeq1, d.aaSeq2) //
								, EntropySeq.entropy(d.aaSeq1, d.aaSeq2) //
								, EntropySeq.variationOfInformation(d.aaSeq1, d.aaSeq2) //
								, EntropySeq.condEntropy(d.aaSeq1, d.aaSeq2) //
								, EntropySeq.condEntropy(d.aaSeq2, d.aaSeq1) //
								, EntropySeq.entropy(d.aaSeq1) //
								, EntropySeq.entropy(d.aaSeq2) //
								, EntropySeq.conservation(d.aaSeq1) //
								, EntropySeq.conservation(d.aaSeq2) //
								) //
				);

		//---
		// Count first 'AA' (all)
		//---
		CountByType countFirstAaAll = new CountByType();
		aaContactsUniq.stream().forEach(d -> countFirstAaAll.inc(d.getAaPair()));
		System.err.println("Count fist AA (all):\n" + Gpr.prependEachLine("COUNT_AA\t", countFirstAaAll.toStringSort()));

		//---
		// Count first 'AA' (not-fully conserved)
		//---
		CountByType countFirstAa = new CountByType();
		aaContactsUniq.stream() //
				.filter(d -> EntropySeq.conservation(d.aaSeq1) < 1.0 && EntropySeq.conservation(d.aaSeq2) < 1.0) // Do not calculate on fully conserved sequences (entropy is zero)
				.forEach(d -> countFirstAa.addScore(d.getAaPair(), f.apply(d))) //
		;
		System.err.println("Count fist AA (non-fully conserved) " + type + " :\n" + Gpr.prependEachLine("COUNT_AA_NON_FULL_CONS_" + type + "\t", countFirstAa.toStringSort()));

		//---
		// Count first 'AA' with annotations (all)
		//---
		CountByType countFirstAaAnnAll = new CountByType();
		aaContactsUniq.stream() //
				.filter(d -> !d.annotations1.isEmpty() && !d.annotations2.isEmpty()) // Only entries having annotations
				.forEach( //
						d -> d.getAaPairAnnotations().forEach(ap -> countFirstAaAnnAll.inc(ap)) //
				) //
		;
		System.err.println("Count fist AA with annotations (all):\n" + Gpr.prependEachLine("COUNT_AA_NEXTPROT_" + type + "\t", countFirstAaAnnAll.toStringSort()));

		//---
		// Count first 'AA' with annotations (not-fully conserved)
		//---
		CountByType countFirstAaAnn = new CountByType();
		aaContactsUniq.stream() //
				.filter(d -> !d.annotations1.isEmpty() && !d.annotations2.isEmpty()) // Only entries having annotations
				.filter(d -> EntropySeq.conservation(d.aaSeq1) < 1.0 && EntropySeq.conservation(d.aaSeq2) < 1.0) // Do not calculate on fully conserved sequences (entropy is zero)
				.forEach( //
						d -> d.getAaPairAnnotations().forEach( // Add to all annotation pairs
								ap -> countFirstAaAnn.addScore(ap, f.apply(d)) //
								) //
				) //
		;
		System.err.println("Count fist AA with annotations (non-fully conserved), " + type + " :\n" + Gpr.prependEachLine("COUNT_AA_NON_FULL_CONS_NEXTPROT_" + type + "\t", countFirstAaAnn.toStringSort()));

	}

	/**
	 * Run Mutual Information
	 */
	void runAaContactStatsN(String type, int numBases) {
		if (numBases <= 0) throw new RuntimeException("Parameter numBases must be positive!");

		// Load
		load();

		// Select which function to use
		MsaSimilarity sim = null;
		switch (type.toLowerCase()) {
		case "mi":
			sim = numBases == 0 ? new MsaSimilarityMutInf(msas) : new MsaSimilarityN(msas, numBases, InformationFunction.MI);
			break;

		case "varinf":
			sim = numBases == 0 ? new MsaDistanceVarInf(msas) : new MsaSimilarityN(msas, numBases, InformationFunction.VARINF);
			break;

		case "hcondxy":
			sim = numBases == 0 ? new MsaSimilarityCondEntropy(msas) : new MsaSimilarityN(msas, numBases, InformationFunction.HCONDXY);
			break;

		default:
			throw new RuntimeException("Unknown type '" + type + "'");
		}

		// Run
		Timer.showStdErr("Sort by position");
		DistanceResults aaContactsUniq = new DistanceResults();
		aaContacts.stream() //
				.filter(d -> !d.aaSeq1.isEmpty() && !d.aaSeq2.isEmpty()) // Filter out empty sequences
				.filter(d -> msas.getMsa(d.msa1) != null && msas.getMsa(d.msa2) != null) // Filter out missing entries
				.forEach(d -> aaContactsUniq.collectMin(d, d.toStringPos()));
		aaContactsUniq.addMins(); // Move 'best' results from hash to list

		//---
		// Show MI and conservation
		//---
		MsaSimilarity simfin = sim;
		aaContactsUniq.stream().forEach(d -> System.out.printf("%s\t%.6e\n", d, simfin.calc(d)));

		// Show distribution
		System.err.println(sim);
	}

	/**
	 * Show AA in contact that have an entry in idMapper
	 */
	void runAaFilterIdMap() {
		load();
		aaContacts.stream() //
				.filter(d -> idMapper.hasEntry(d.getTrIdNoSub(), d.pdbId, d.pdbChainId)) //
				.forEach(System.out::println);
	}

	void runAaFrequencies() {
		load();

		// Calculate AA frequencies
		long aaCount[] = new long[GprSeq.AMINO_ACIDS.length];
		msas.stream() //
				.forEach( // Count all AA in this MSA
						msa -> {
							for (int col = 0; col < msa.length(); col++)
								for (int row = 0; row < msa.size(); row++) {
									byte aa = msa.getCode(row, col);
									if (aa >= 0) aaCount[aa]++;
								}
						});

		// Show results
		double sum = 0;
		for (byte aa = 0; aa < aaCount.length; aa++)
			sum += aaCount[aa];

		for (byte aa = 0; aa < aaCount.length; aa++)
			System.out.println(GprSeq.code2aa(aa) + "\t" + aaCount[aa] / sum);
	}

	/**
	 * Add MSA sequences to 'AA contact' data
	 */
	void runAddMsaSeqs() {
		load();

		// Sanity check: Make sure MSA protein sequences match genome's protein data
		Timer.showStdErr("Checking MSA proteing sequences vs. genome protein sequences");
		pdbGenome.checkSequenceMsaTr();
		System.err.println("Totals:\n" + pdbGenome.countMatch);
		pdbGenome.resetStats();

		// Add MSA sequences to 'AA contact' entries
		Timer.showStdErr("Adding MSA sequences");
		aaContacts.forEach(d -> pdbGenome.mapToMsa(msas, d));
		System.err.println("Totals:\n" + pdbGenome.countMatch);

		System.err.println("Mapped AA sequences:\n");
		aaContacts.stream().filter(d -> d.aaSeq1 != null).forEach(System.out::println);
	}

	/**
	 * Run Mutual Information
	 */
	void runBackground(String type, int numBases, int numSamples) {
		// Load
		load();

		// Select which function to use
		MsaSimilarity sim = null;
		switch (type.toLowerCase()) {
		case "mi":
			sim = numBases == 0 ? new MsaSimilarityMutInf(msas) : new MsaSimilarityN(msas, numBases, InformationFunction.MI);
			break;

		case "varinf":
			sim = numBases == 0 ? new MsaDistanceVarInf(msas) : new MsaSimilarityN(msas, numBases, InformationFunction.VARINF);
			break;

		case "hcondxy":
			sim = numBases == 0 ? new MsaSimilarityCondEntropy(msas) : new MsaSimilarityN(msas, numBases, InformationFunction.HCONDXY);
			break;

		default:
			throw new RuntimeException("Unknown type '" + type + "'");
		}

		// Run
		sim.backgroundDistribution(numSamples);

		// Show distribution
		System.err.println(sim);
	}

	void runBayes() {
		//	// Calculate Bayes Factor
		//
		//	// For each MSA...
		//	mltm.calcPi();
		//	for (MultipleSequenceAlignment msa1 : msas) {
		//		for (MultipleSequenceAlignment msa2 : msas) {
		//			// Make sure the MSAs are far away from each other
		//			if (msa2.compareTo(msa1) < MIN_DISTANCE) continue;
		//
		//			// All pair of positions
		//			for (int pos1 = 0; pos1 < msa1.length(); pos1++) {
		//				if (msa1.isSkip(pos1)) continue;
		//
		//				// Log likelihood at position 1
		//				double loklik1 = mltm.logLikelyhood(msa1, pos1);
		//
		//				for (int pos2 = 0; pos2 < msa2.length(); pos2++) {
		//					if (msa2.isSkip(pos2)) continue;
		//
		//					// Log likelihood at position 2
		//					double loklik2 = mltm.logLikelyhood(msa2, pos2);
		//					System.out.println(msa1.getId() + "\t" + msa2.getId() + "\t" + pos1 + "\t" + pos2 + "\t" + loklik1 + "\t" + loklik2);
		//
		//					// Combined log likelihood
		//					mltm.logLikelyhood(msa1, pos1, msa2, pos2);
		//				}
		//			}
		//		}
		//	}
	}

	/**
	 * Conservation statistics
	 */
	void runConservation() {
		load();

		// N bases
		System.out.println("Window\tTotal_bases\tConserved\tConserved%");
		for (int n = 0; n < 10; n++) {
			int num = n;
			Counter total = new Counter();
			Counter conserved = new Counter();

			//---
			// Count for all MSAs
			//---
			msas.getMsas().stream() //
					.filter(msa -> msa.length() > num) //
					.forEach(msa -> IntStream.range(0, msa.length() - num) //
							.peek(i -> total.inc()) //
							.filter(i -> msa.isFullyConserved(i, num)) //
							.forEach(i -> conserved.inc()) //
					);

			//---
			// Count number of 'fully conserved' AA windows of n-columns around 'AA in contact'
			//---
			Counter totalIc = new Counter();
			Counter conservedIc = new Counter();
			aaContacts.stream() //
					.filter(d -> !d.msa1.isEmpty() && !d.msa2.isEmpty() && msas.getMsa(d.msa1) != null) //
					.peek(d -> totalIc.inc()) //
					.map(d -> new Pair<String[], String[]>(msas.findColSequences(d.msa1, d.msaIdx1, num), msas.findColSequences(d.msa2, d.msaIdx2, num))) //
					.filter(p -> isFullyConserved(p.getFirst()) && isFullyConserved(p.getSecond())) //
					.forEach(d -> conservedIc.inc()) //
			;

			//---
			// Show results
			//---
			int winSize = 2 * num + 1;
			double consPerc = (100.0 * conserved.get()) / total.get();
			double consIcPerc = (100.0 * conservedIc.get()) / totalIc.get();
			System.out.println(winSize //
					+ "\t" + total //
					+ "\t" + conserved //
					+ "\t" + String.format("%.2f%%", consPerc) //
					+ "\t" + totalIc //
					+ "\t" + conservedIc //
					+ "\t" + String.format("%.2f%%", consIcPerc) //
			);
		}
	}

	/**
	 * Map PDB entries to GeneID & TranscriptID
	 */
	void runMapPdbGene() {
		load();
		pdbGenome.checkSequencePdbTr();
	}

	/**
	 * Map PDB entries to GeneID & TranscriptID keeping only the "best" mapping
	 */
	void runMapPdbGeneBest() {
		load();
		Collection<IdMapperEntry> best = idMapper.best(aaContacts);
		best.stream().sorted().forEach(System.out::println);
	}

	void runNextProt() {
		nextProt = true;
		load();

		// Add NextProt annotations
		aaContacts.forEach(d -> pdbGenome.nextProt(d));
	}

	/**
	 * Run Pdb distance
	 */
	void runPdbDist(double distThreshold, int aaMinSeparation) {
		load();

		// Run analysis
		PdbDistanceAnalysis pdDist = new PdbDistanceAnalysis(pdbDir, distThreshold, aaMinSeparation, idMapper);
		pdDist.run();
		System.out.println(Gpr.prependEachLine("DISTANCE_AA_HISTO\t", pdDist));
	}

	/**
	 * Estimate Q matrix from MSA and Phylogenetic-Tree
	 */
	void runQhat() {
		load();

		// Estimate
		EstimateTransitionMatrix mltm = new EstimateTransitionMatrix(tree, msas);
		mltm.setVerbose(true);
		Q = mltm.estimateTransitionMatrix();

		// Sanity checks
		RealVector z = Q.operate(mltm.calcPi());
		System.out.println("Q matrix:\n" + Gpr.prependEachLine("Q_HAT_MATRIX" + "\t", Q));

		System.out.println("Q's Eigenvalues: ");
		double maxLambda = Q.checkEien(true);

		System.out.println("Q_HAT_SUMMARY" //
				+ "\tNorm( Q * pi ):\t" + z.getNorm() //
				+ "\tmax_lambda:\t" + maxLambda //
				+ "\thas_complex_eigenvalues:\t" + Q.hasComplexEigenvalues() //
				+ "\thas_negative_off_diagonal_entries:\t" + Q.hasNegativeOffDiagonalEntries() //
				+ "\tis_zero:\t" + Q.isZero() //
				+ "\tis_symmetric:\t" + Q.isSymmetric() //
		);
	}

	/**
	 * Calculate Qhat2 (400x400 AA-Pairs transition matrix)
	 */
	void runQhat2() {
		load();

		// Estimate
		EstimateTransitionMatrixPairs etm = new EstimateTransitionMatrixPairs(tree, msas, aaContacts);
		etm.setVerbose(true);
		Q2 = etm.estimateTransitionMatrix();

		// Sanity checks
		RealVector z = Q2.operate(etm.calcPi());
		System.out.println("Q2 matrix:\n" + Gpr.prependEachLine("Q_HAT2_MATRIX\t", Q2));

		System.out.println("Q's Eigenvalues: ");
		double maxLambda = Q2.checkEien(true);

		System.out.println("Q_HAT2_SUMMARY" //
				+ "\tNorm( Q2 * pi ):\t" + z.getNorm() //
				+ "\tmax_lambda:\t" + maxLambda //
				+ "\thas_complex_eigenvalues:\t" + Q2.hasComplexEigenvalues() //
				+ "\thas_negative_off_diagonal_entries:\t" + Q2.hasNegativeOffDiagonalEntries() //
				+ "\tis_zero:\t" + Q2.isZero() //
				+ "\tis_symmetric:\t" + Q2.isSymmetric() //
		);

	}

	/**
	 * Test
	 */
	void runTest(String fileName) {
		TransitionMatrix qhat = new TransitionMatrix(fileName);
		System.out.println("\n\nMATRIX :\n" + qhat);
		System.out.println("\n\nLOG    :\n" + qhat.log());
		System.out.println("\n\nEXP    :\n" + qhat.exp(1));
	}

	/**
	 * Transition
	 */
	void runTransitions(int numSamples) {
		load();

		//---
		// Calculate 'AA in contact' transitions: Single AA
		//---
		System.err.println("Calculating transitions: Sinlge AA, 'in contact':");

		TransitionsAa transAa = new TransitionsAa();
		aaContacts.stream() //
				.filter(d -> msas.getMsa(d.msa1) != null && msas.getMsa(d.msa2) != null) //
				.forEach(d -> {
					transAa.count(msas.getMsa(d.msa1).getColumn(d.msaIdx1));
					transAa.count(msas.getMsa(d.msa2).getColumn(d.msaIdx2));
				});

		System.out.println(Gpr.prependEachLine("AA_SINGLE_IN_CONTACT\t", transAa));

		//---
		// Calculate 'null' transitions: Single AA
		//---
		System.err.println("Calculating transitions: sinlge AA, 'null':");
		TransitionsAa zero = new TransitionsAa(); // Identity
		TransitionsAa sum = msas.stream() //
				.map(msa -> transitions(msa)) // Calculate transitions
				.reduce(zero, (t1, t2) -> t1.add(t2)) // Reduce by adding
		;
		System.out.println(Gpr.prependEachLine("AA_SINGLE_BG\t", sum));

		//---
		// Calculate transitions pairs: AA in contact
		//---
		TransitionsAaPairs transPairs = new TransitionsAaPairs();
		aaContacts.stream()//
				.filter(d -> !d.aaSeq1.isEmpty() && !d.aaSeq2.isEmpty()) //
				.forEach(d -> transPairs.count(d)) //
		;
		System.out.println(Gpr.prependEachLine("AA_PAIRS_IN_CONTACT\t", transPairs));

		//---
		// Calculate transition pairs: Background using random sampling
		//---
		TransitionsAaPairs transPairsBgRand = transitionPairsBgRand(numSamples);
		System.out.println(Gpr.prependEachLine("AA_PAIRS_BG_RAND\t", transPairsBgRand));

		//---
		// Calculate transition pairs: Background using all pairs within protein
		//---
		TransitionsAaPairs transPairsBg = transitionPairsBg();
		System.out.println(Gpr.prependEachLine("AA_PAIRS_BG_WITHIN_PROT\t", transPairsBg));
	}

	/**
	 * Check consistency between MSA and tree
	 */
	void sanityCheck(LikelihoodTree tree, MultipleSequenceAlignmentSet msas) {
		// Sanity check: Make sure that the alignment and the tree match
		String speciesMsa = String.join("\t", msas.getSpecies());
		String speciesTree = String.join("\t", tree.childNames());

		if (!speciesTree.equals(speciesMsa)) throw new RuntimeException("Species form MSA and Tree do not match:\n\tMSA : " + speciesMsa + "\n\tTree: " + speciesTree);

		System.err.println("\nSpecies [" + tree.childNames().size() + "]: " + speciesTree);
	}

	// Select which function to use
	Function<DistanceResult, Double> selectFunction(String type) {
		switch (type.toLowerCase()) {
		case "mi":
			return d -> EntropySeq.variationOfInformation(d.aaSeq1, d.aaSeq2);

		case "varinf":
			return d -> EntropySeq.mutualInformation(d.aaSeq1, d.aaSeq2);

		default:
			throw new RuntimeException("Unknown type '" + type + "'");
		}
	}

	/**
	 * Calculate transitions: Background
	 */
	TransitionsAaPairs transitionPairsBg() {
		Timer.showStdErr("Calculating transition pairs 'null' distribution: All pairs within protein");

		msas.calcSkip(); // Pre-calculate skip on all MSAs

		// Count transitions
		int maxCount = msas.getTrIDs().size();
		Counter count = new Counter();
		TransitionsAaPairs zero = new TransitionsAaPairs(); // Identity
		TransitionsAaPairs sum = msas.getTrIDs() //
				.parallelStream() //
				.map(id -> transitionPairsBg(id, (int) count.inc(), maxCount)) //
				.reduce(zero, (t1, t2) -> t1.add(t2)) // Reduce by adding
		;

		return sum;
	}

	/**
	 * Calculate background distribution of transitions using all "within protein" pairs
	 */
	TransitionsAaPairs transitionPairsBg(String trId, int count, int maxCount) {
		TransitionsAaPairs trans = new TransitionsAaPairs();
		List<MultipleSequenceAlignment> msasTr = msas.getMsas(trId);

		int totalLen = msasTr.stream().mapToInt(m -> m.length()).sum();
		System.err.println("\t" + trId + "\tNum. MSAs: " + msasTr.size() + "\tTotal len: " + totalLen + "\t" + count + "/" + maxCount);
		msasTr.forEach(m -> System.err.println("\t\t" + m.getId() + "\t" + m.length()));

		// Iterate though all sequence alignment on this transcript
		for (int mi = 0; mi < msasTr.size(); mi++) {
			MultipleSequenceAlignment msai = msasTr.get(mi);
			int maxi = msai.length();

			for (int mj = mi; mj < msasTr.size(); mj++) {
				MultipleSequenceAlignment msaj = msasTr.get(mj);
				int maxj = msaj.length();

				// Compare all rows
				for (int i = 0; i < maxi; i++) {
					int minj = 0;
					if (msai.getId().equals(msaj.getId())) minj = i + 1;

					for (int j = minj; j < maxj; j++)
						trans.count(msai.getColumn(i), msaj.getColumn(j));
				}
			}
		}

		return trans;
	}

	/**
	 * Calculate transitions background (one iteration
	 */
	void transitionPairsBg(TransitionsAaPairs trans, Random random) {
		// Random msa and positions
		MultipleSequenceAlignment msa = null;
		int idx1 = 0, idx2 = 0;
		while (msa == null || idx1 == idx2) {
			msa = msas.rand(random);
			idx1 = random.nextInt(msa.length());
			idx2 = random.nextInt(msa.length());
		}

		// Calculate transitions
		trans.count(msa.getColumn(idx1), msa.getColumn(idx2));
	}

	/**
	 * Calculate transitions: Background
	 */
	TransitionsAaPairs transitionPairsBgRand(int numberOfSamples) {
		// Initialize
		TransitionsAaPairs trans = new TransitionsAaPairs();
		Random random = new Random();
		msas.calcSkip(); // Pre-calculate skip on all MSAs

		// Count transitions
		Timer.showStdErr("Calculating transition pairs 'null' distribution: " + numberOfSamples + " iterations");
		IntStream.range(1, numberOfSamples) //
				.peek(i -> Gpr.showMark(i, 1000)) //
				.forEach(i -> transitionPairsBg(trans, random));

		return trans;
	}

	/**
	 * Calculate AA transitions
	 */
	TransitionsAa transitions(MultipleSequenceAlignment msa) {
		int max = msa.length();
		TransitionsAa trans = new TransitionsAa();
		System.err.println("\t" + msa.getId() + "\tlen: " + max);

		// Compare all rows
		for (int i = 0; i < max; i++)
			trans.count(msa.getColumn(i));

		return trans;
	}

	@Override
	public void usage(String message) {
		if (message != null) System.err.println("Error: " + message + "\n");
		System.err.println("Usage: " + this.getClass().getSimpleName() + " cmd options");

		System.err.println("Command 'aaContactStats'   : " + this.getClass().getSimpleName() + " aaContactStats type aa_contact.nextprot.txt ");
		System.err.println("Command 'aaContactStatsN'  : " + this.getClass().getSimpleName() + " aaContactStatsN type number_of_bases phylo.nh multiple_alignment_file.fa aa_contact.nextprot.txt");
		System.err.println("Command 'aaFreqs'          : " + this.getClass().getSimpleName() + " aaFreqs phylo.nh multiple_alignment_file.fa");
		System.err.println("Command 'addMsaSeqs'       : " + this.getClass().getSimpleName() + " addMsaSeqs snpeff.config genome phylo.nh multiple_alignment_file.fa id_map.txt aa_contact.txt ");
		System.err.println("Command 'background'       : " + this.getClass().getSimpleName() + " background number_of_bases number_of_samples phylo.nh multiple_alignment_file.fa");
		System.err.println("Command 'conservation'     : " + this.getClass().getSimpleName() + " conservation phylo.nh multiple_alignment_file.fa");
		System.err.println("Command 'corr'             : " + this.getClass().getSimpleName() + " corr phylo.nh multiple_alignment_file.fa");
		System.err.println("Command 'mapPdbGenome'     : " + this.getClass().getSimpleName() + " mapPdbGenome snpeff.config genome pdbDir idMapFile");
		System.err.println("Command 'mapPdbGenomeBest' : " + this.getClass().getSimpleName() + " mapPdbGenomeBest idMapFile aa_contact.txt");
		System.err.println("Command 'pdbdist'          : " + this.getClass().getSimpleName() + " pdbdist distanceThreshold aaMinSeparation path/to/pdb/dir id_map.txt");
		System.err.println("Command 'qhat'             : " + this.getClass().getSimpleName() + " qhat phylo.nh multiple_sequence_alignment.fa transition_matrix.txt");
		System.err.println("Command 'transitions'      : " + this.getClass().getSimpleName() + " transitions num_samples phylo.nh multiple_alignment_file.fa aa_contact.nextprot.txt ");
		System.exit(-1);
	}

}
