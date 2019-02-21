/* 
 * The MIT License
 *
 * Copyright 2018 The OpenNARS authors.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.opennars.control.concept;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.opennars.control.DerivationContext;
import org.opennars.entity.BudgetValue;
import org.opennars.entity.Concept;
import org.opennars.entity.Sentence;
import org.opennars.entity.Stamp;
import org.opennars.entity.Task;
import org.opennars.entity.TaskLink;
import org.opennars.entity.TermLink;
import org.opennars.entity.TruthValue;
import org.opennars.inference.RuleTables;
import org.opennars.inference.TemporalRules;
import org.opennars.inference.TruthFunctions;
import org.opennars.interfaces.Timable;
import org.opennars.io.events.OutputHandler;
import org.opennars.language.*;
import org.opennars.main.Nar;
import org.opennars.main.Parameters;
import org.opennars.operator.Operator;
import org.opennars.operator.mental.Anticipate;
import org.opennars.storage.Memory;

import static org.opennars.inference.UtilityFunctions.c2w;
import static org.opennars.inference.UtilityFunctions.w2c;

/**
 *
 * @author Patrick Hammer
 */
public class ProcessAnticipation {

    public static void anticipate(final DerivationContext nal, final Sentence mainSentence, final BudgetValue budget, 
            final long mintime, final long maxtime, final float priority, Map<Term,Term> substitution) {
        //derivation was successful and it was a judgment event
        final Stamp stamp = new Stamp(nal.time, nal.memory);
        stamp.setOccurrenceTime(Stamp.ETERNAL);
        float eternalized_induction_confidence = nal.memory.narParameters.ANTICIPATION_CONFIDENCE;
        final Sentence s = new Sentence(
            mainSentence.term,
            mainSentence.punctuation,
            new TruthValue(0.0f, eternalized_induction_confidence, nal.narParameters),
            stamp);
        final Task t = new Task(s, new BudgetValue(0.99f,0.1f,0.1f, nal.narParameters), Task.EnumType.DERIVED); //Budget for one-time processing
        Term specificAnticipationTerm = ((CompoundTerm)((Statement) mainSentence.term).getPredicate()).applySubstitute(substitution);
        final Concept c = nal.memory.concept(specificAnticipationTerm); //put into consequence concept
        if(c != null /*&& mintime > nal.memory.time()*/ && c.observable && (mainSentence.getTerm() instanceof Implication || mainSentence.getTerm() instanceof Equivalence) && 
                mainSentence.getTerm().getTemporalOrder() == TemporalRules.ORDER_FORWARD) {
            Concept.AnticipationEntry toDelete = null;
            Concept.AnticipationEntry toInsert = new Concept.AnticipationEntry(priority, t, mintime, maxtime);
            boolean fullCapacity = c.anticipations.size() >= nal.narParameters.ANTICIPATIONS_PER_CONCEPT_MAX;
            //choose an element to replace with the new, in case that we are already at full capacity
            if(fullCapacity) {
                for(Concept.AnticipationEntry entry : c.anticipations) {
                    if(priority > entry.negConfirmationPriority /*|| t.getPriority() > c.negConfirmation.getPriority() */) {
                        //prefer to replace one that is more far in the future, takes longer to be disappointed about
                        if(toDelete == null || entry.negConfirm_abort_maxtime > toDelete.negConfirm_abort_maxtime) {
                            toDelete = entry;
                        }
                    }
                }
            }
            //we were at full capacity but there was no item that can be replaced with the new one
            if(fullCapacity && toDelete == null) {
                return;
            }
            if(toDelete != null) {
                c.anticipations.remove(toDelete);
            }
            c.anticipations.add(toInsert);
            final Statement impOrEqu = (Statement) toInsert.negConfirmation.sentence.term;
            final Concept ctarget = nal.memory.concept(impOrEqu.getPredicate());
            if(ctarget != null) {
                Operator anticipate_op = ((Anticipate)c.memory.getOperator("^anticipate"));
                if(anticipate_op != null && anticipate_op instanceof Anticipate) {
                    ((Anticipate)anticipate_op).anticipationFeedback(impOrEqu.getPredicate(), null, c.memory, nal.time);
                }
            }
            nal.memory.emit(OutputHandler.ANTICIPATE.class, specificAnticipationTerm); //disappoint/confirm printed anyway
        }
   
    }

    /**
     * Process outdated anticipations within the concept,
     * these which are outdated generate negative feedback
     * 
     * @param narParameters The reasoner parameters
     * @param concept The concept which potentially outdated anticipations should be processed
     * @param nar reasoner
     */
    public static void maintainDisappointedAnticipations(final Parameters narParameters, final Concept concept, final Nar nar) {
        //here we can check the expiration of the feedback:
        List<Concept.AnticipationEntry> confirmed = new ArrayList<>();
        List<Concept.AnticipationEntry> disappointed = new ArrayList<>();
        for(Concept.AnticipationEntry entry : concept.anticipations) {
            if(entry.negConfirmation == null || nar.time() <= entry.negConfirm_abort_maxtime) {
                continue;
            }
            //at first search beliefs for input tasks:
            boolean gotConfirmed = false;
            if(narParameters.RETROSPECTIVE_ANTICIPATIONS) {
                for(final TaskLink tl : concept.taskLinks) { //search for input in tasklinks (beliefs alone can not take temporality into account as the eternals will win)
                    final Task t = tl.targetTask;
                    if(t!= null && t.sentence.isJudgment() && t.isInput() && !t.sentence.isEternal() && t.sentence.truth.getExpectation() > concept.memory.narParameters.DEFAULT_CONFIRMATION_EXPECTATION &&
                            CompoundTerm.replaceIntervals(t.sentence.term).equals(CompoundTerm.replaceIntervals(concept.getTerm()))) {
                        if(t.sentence.getOccurenceTime() >= entry.negConfirm_abort_mintime && t.sentence.getOccurenceTime() <= entry.negConfirm_abort_maxtime) {
                            confirmed.add(entry);
                            gotConfirmed = true;
                            break;
                        }
                    }
                }
            }
            if(!gotConfirmed) {
                disappointed.add(entry);
            }
        }
        //confirmed by input, nothing to do
        if(confirmed.size() > 0) {
            concept.memory.emit(OutputHandler.CONFIRM.class,concept.getTerm());
        }
        concept.anticipations.removeAll(confirmed);
        //not confirmed and time is out, generate disappointment
        if(disappointed.size() > 0) {
            concept.memory.emit(OutputHandler.DISAPPOINT.class,concept.getTerm());
        }
        for(Concept.AnticipationEntry entry : disappointed) {
            negConfirmationAdjustTruth(entry.negConfirmation.getTerm(), nar, concept.memory, nar);
            concept.anticipations.remove(entry);
        }
    }

    private static TruthValue adjustEvidence(final TruthValue beliefTv, final float amountOfConfirmationEvidence, final Parameters reasonerParameters) {
        // compute amount of evidence
        // see draft of book "Non-Axiomatic Logic: A Model of Intelligent Reasoning" page 30
        final float wBefore = c2w(beliefTv.getConfidence(), reasonerParameters);//k * c / (1.0f - c);

        // adjust amount of evidence.
        final float wAfter = Math.max(wBefore + amountOfConfirmationEvidence, 0.0f);

        final float cAfter = w2c(wAfter, reasonerParameters);//wAfter / (wAfter + k);
        float fAfter = wBefore / wAfter;
        fAfter *= beliefTv.getFrequency();
        return new TruthValue(fAfter, cAfter, reasonerParameters);
    }

    private static void negConfirmationAdjustTruth(final Term term, final Nar nar, final Memory mem, final Timable time) {
        final Term termWithReplacedIntervals = CompoundTerm.replaceIntervals(term);

        Concept c = nar.memory.concepts.get(termWithReplacedIntervals);

        Task beliefToAdjust = null;

        for(final Task iBelief : c.beliefs) {
            if( iBelief.sentence.term.equals(term) ) {
                beliefToAdjust = iBelief;
                break;
            }
        }

        if (beliefToAdjust == null) {
            return; // can't adjust truth of non eisting belief
        }

        // Negative because we subtract because we have less evidence for the negative confirmation.
        // It has less evidence, because it will be used for revision - and we don't want to revise to much toward freq=0
        float amountOfConfirmationEvidence = -0.4f;//0.8f;

        float negConfirmationBeliefConfidence = adjustEvidence(beliefToAdjust.sentence.truth, amountOfConfirmationEvidence, nar.narParameters).getConfidence();
        //float negConfirmationBeliefFrequency =

        // build new truthvalue - frequency is zero because it is a negative confirmation (disapointment)
        final TruthValue tv = new TruthValue(0.0f, negConfirmationBeliefConfidence, nar.narParameters);

        // revise
        final TruthValue adjustedTv = TruthFunctions.revision(beliefToAdjust.sentence.truth, tv, nar.narParameters);

        //final TruthValue adjustedTv = adjustEvidence(beliefToAdjust.sentence.truth, amountOfConfirmationEvidence, nar.narParameters);

        beliefToAdjust.sentence = new Sentence(
            beliefToAdjust.sentence.term,
            beliefToAdjust.sentence.punctuation,
            adjustedTv,
            beliefToAdjust.sentence.stamp
        );
    }

    /**
     * Whether a processed judgement task satisfies the anticipations within concept
     * 
     * @param task The judgement task be checked
     * @param concept The concept that is processed
     * @param nal The derivation context
     */
    public static void confirmAnticipation(Task task, Concept concept, final DerivationContext nal) {
        final boolean satisfiesAnticipation = task.isInput() && !task.sentence.isEternal();
        final boolean isExpectationAboveThreshold = task.sentence.truth.getExpectation() > nal.narParameters.DEFAULT_CONFIRMATION_EXPECTATION;
        List<Concept.AnticipationEntry> confirmed = new ArrayList<>();
        for(Concept.AnticipationEntry entry : concept.anticipations) {
            if(satisfiesAnticipation && isExpectationAboveThreshold && task.sentence.getOccurenceTime() > entry.negConfirm_abort_mintime) {
                confirmed.add(entry);
            }
        }
        if(confirmed.size() > 0) {
            nal.memory.emit(OutputHandler.CONFIRM.class, concept.getTerm());
        }
        concept.anticipations.removeAll(confirmed);
    }
    
    /**
     * Fire predictictive inference based on beliefs that are known to the concept's neighbours
     * 
     * @param judgementTask judgement task
     * @param concept concept that is processed
     * @param nal derivation context
     * @param time used to retrieve current time
     * @param tasklink coresponding tasklink
     */
    public static void firePredictions(final Task judgementTask, final Concept concept, final DerivationContext nal, Timable time, TaskLink tasklink) {
        if(!judgementTask.sentence.isEternal() && judgementTask.isInput() && judgementTask.sentence.isJudgment()) {
            for(TermLink tl : concept.termLinks) {
                Term term = tl.getTarget();
                Concept tc = nal.memory.concept(term);
                if(tc != null && !tc.beliefs.isEmpty() && term instanceof Implication) {
                    Implication imp = (Implication) term;
                    if(imp.getTemporalOrder() == TemporalRules.ORDER_FORWARD) {
                        Term precon = imp.getSubject();
                        Term component = precon;
                        if(precon instanceof Conjunction) {
                            Conjunction conj = (Conjunction) imp.getSubject();
                            if(conj.getTemporalOrder() == TemporalRules.ORDER_FORWARD && conj.term.length == 2 && conj.term[1] instanceof Interval) {
                                component = conj.term[0]; //(&/,a,+i), so use a
                            }
                        }
                        if(CompoundTerm.replaceIntervals(concept.getTerm()).equals(CompoundTerm.replaceIntervals(component))) {
                            //trigger inference of the task with the belief
                            DerivationContext cont = new DerivationContext(nal.memory, nal.narParameters, time);
                            cont.setCurrentTask(judgementTask); //a
                            cont.setCurrentBeliefLink(tl); // a =/> b
                            cont.setCurrentTaskLink(tasklink); // a
                            cont.setCurrentConcept(concept); //a
                            cont.setCurrentTerm(concept.getTerm()); //a
                            RuleTables.reason(tasklink, tl, cont); //generate b
                        }
                    }
                }
            }
        }
    }
}
