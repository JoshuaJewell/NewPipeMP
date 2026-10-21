/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive.ui

import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import org.schabi.newpipe.R
import org.schabi.newpipe.adaptive.AdaptivePlayerIntegration
import org.schabi.newpipe.adaptive.AdaptiveShuffleEngine
import org.schabi.newpipe.adaptive.AdaptiveShuffleHolder
import org.schabi.newpipe.adaptive.ArmComparison
import org.schabi.newpipe.adaptive.Chooser
import org.schabi.newpipe.adaptive.FeatureSchema
import org.schabi.newpipe.adaptive.RepetitionMetrics

class AdaptiveShuffleDebugFragment : Fragment() {

    private val disposables = CompositeDisposable()
    private val timestampFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val positiveColour = Color.parseColor("#2E7D32")
    private val negativeColour = Color.parseColor("#C62828")
    private val exploitChipColour = Color.parseColor("#1565C0")
    private val exploreChipColour = Color.parseColor("#EF6C00")
    private val randomChipColour = Color.parseColor("#616161")
    private val userChipColour = Color.parseColor("#2E7D32")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_adaptive_shuffle_debug, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        disposables.add(
            Single.fromCallable { AdaptiveShuffleHolder.get(requireContext()) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ integration ->
                    refresh(view, integration)
                    view.findViewById<Button>(R.id.rebuildButton).setOnClickListener {
                        disposables.add(
                            Single.fromCallable {
                                integration.engine.rebuildFromPending(integration.telemetryIndex())
                            }
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({ report ->
                                    Toast.makeText(
                                        requireContext(),
                                        getString(
                                            R.string.adaptive_shuffle_rebuild_report,
                                            report.ingested,
                                            report.fromStoredOutcome,
                                            report.fromTelemetry,
                                            report.carriedOver,
                                            report.skipped
                                        ),
                                        Toast.LENGTH_LONG
                                    ).show()
                                    refresh(view, integration)
                                }, { _ -> })
                        )
                    }
                }, { _ -> })
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        disposables.dispose()
    }

    private fun refresh(view: View, integration: AdaptivePlayerIntegration) {
        val engine = integration.engine
        val nEvents = engine.numEvents()
        view.findViewById<TextView>(R.id.eventsCount).text =
            getString(R.string.adaptive_shuffle_events_label, nEvents)

        renderTheta(view, engine.theta())

        disposables.add(
            Single.fromCallable {
                val report = engine.repetitionMetrics()
                val titles = HashMap<Long, String>()
                val ids = report.recurringTracks.map { it.streamId } + listOfNotNull(report.topTrackId)
                for (id in ids) engine.streamTitle(id)?.let { titles[id] = it }
                report to titles
            }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ (report, titles) ->
                    renderMetrics(view, report, titles)
                }, { _ -> })
        )

        disposables.add(
            Single.fromCallable { engine.trackOffsets() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ (liked, disliked) ->
                    renderOffsets(view, liked, disliked)
                }, { _ -> })
        )

        disposables.add(
            Single.fromCallable { engine.armComparison() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ report ->
                    renderComparison(view, report)
                }, { _ -> })
        )

        // Recent decisions involve DB reads for title resolution; do off main thread.
        disposables.add(
            Single.fromCallable { engine.recentDecisions(20) }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe({ decisions ->
                    renderDecisions(view, decisions)
                }, { _ -> })
        )
    }

    private fun renderComparison(view: View, report: ArmComparison.Report) {
        val caption = view.findViewById<TextView>(R.id.compareCaption)
        val body = view.findViewById<TextView>(R.id.compareBody)
        val since = report.since
        if (since == null || report.baseline == null) {
            caption.text = getString(R.string.adaptive_shuffle_compare_empty)
            body.text = ""
            return
        }
        caption.text = getString(
            R.string.adaptive_shuffle_compare_caption,
            DateUtils.getRelativeTimeSpanString(since, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
        )
        val lines = ArrayList<String>()
        report.model?.let { lines += armLine(getString(R.string.adaptive_shuffle_compare_model), it) }
        lines += armLine(getString(R.string.adaptive_shuffle_random), report.baseline)
        lines += ""
        val lift = report.rewardLift
        val half = report.rewardLiftHalfWidth
        if (lift != null && half != null) {
            lines += getString(R.string.adaptive_shuffle_compare_lift, lift, lift - half, lift + half)
        }
        lines += when (report.verdict) {
            ArmComparison.Verdict.NOT_ENOUGH_DATA ->
                getString(R.string.adaptive_shuffle_compare_need_more, ArmComparison.MIN_LISTENS_PER_ARM)

            ArmComparison.Verdict.MODEL_BETTER -> getString(R.string.adaptive_shuffle_compare_model_better)

            ArmComparison.Verdict.BASELINE_BETTER -> getString(R.string.adaptive_shuffle_compare_baseline_better)

            ArmComparison.Verdict.NO_CLEAR_DIFFERENCE -> getString(R.string.adaptive_shuffle_compare_no_difference)
        }
        body.text = lines.joinToString("\n")
    }

    private fun renderOffsets(
        view: View,
        liked: List<AdaptiveShuffleEngine.TrackOffset>,
        disliked: List<AdaptiveShuffleEngine.TrackOffset>
    ) {
        val body = view.findViewById<TextView>(R.id.offsetsBody)
        if (liked.isEmpty() && disliked.isEmpty()) {
            body.text = getString(R.string.adaptive_shuffle_offsets_empty)
            return
        }
        fun row(o: AdaptiveShuffleEngine.TrackOffset) = getString(
            R.string.adaptive_shuffle_offsets_row,
            o.title ?: "#${o.streamId}",
            o.offset,
            o.weightedListens
        )
        val lines = ArrayList<String>()
        if (liked.isNotEmpty()) {
            lines += getString(R.string.adaptive_shuffle_offsets_liked)
            liked.forEach { lines += row(it) }
        }
        if (disliked.isNotEmpty()) {
            if (lines.isNotEmpty()) lines += ""
            lines += getString(R.string.adaptive_shuffle_offsets_disliked)
            disliked.forEach { lines += row(it) }
        }
        body.text = lines.joinToString("\n")
    }

    private fun armLine(name: String, arm: ArmComparison.Arm): String = getString(
        R.string.adaptive_shuffle_compare_arm,
        name,
        arm.listens,
        arm.meanReward,
        (arm.meanCompletion * 100).roundToInt(),
        (arm.completedShare * 100).roundToInt(),
        (arm.earlySkipShare * 100).roundToInt()
    )

    private fun renderMetrics(view: View, report: RepetitionMetrics.Report, titles: Map<Long, String>) {
        val caption = view.findViewById<TextView>(R.id.metricsCaption)
        val body = view.findViewById<TextView>(R.id.metricsBody)
        if (report.windowSize == 0) {
            caption.text = getString(R.string.adaptive_shuffle_metrics_empty)
            body.text = ""
            return
        }
        caption.text = getString(R.string.adaptive_shuffle_metrics_caption, report.windowSize)
        val lines = ArrayList<String>()
        lines += getString(R.string.adaptive_shuffle_metrics_unique, report.uniqueTracks, report.windowSize)
        report.topUploader?.let {
            val offered = report.topUploaderOfferedShare
            lines += if (offered != null) {
                getString(
                    R.string.adaptive_shuffle_metrics_top_uploader_offered,
                    it,
                    (report.topUploaderShare * 100).toInt(),
                    (offered * 100).toInt()
                )
            } else {
                getString(
                    R.string.adaptive_shuffle_metrics_top_uploader,
                    it,
                    (report.topUploaderShare * 100).toInt()
                )
            }
        }
        report.mostOverServed?.let {
            lines += getString(
                R.string.adaptive_shuffle_metrics_over_served,
                it.uploader,
                (it.served * 100).toInt(),
                (it.offered * 100).toInt()
            )
        }
        report.topTrackId?.let {
            lines += getString(
                R.string.adaptive_shuffle_metrics_top_track,
                titles[it] ?: "#$it",
                (report.topTrackShare * 100).toInt()
            )
        }
        if (report.sessionsConsidered > 1 && report.recurringTracks.isNotEmpty()) {
            lines += ""
            lines += getString(R.string.adaptive_shuffle_metrics_sessions, report.sessionsConsidered)
            for (r in report.recurringTracks) {
                lines += getString(
                    R.string.adaptive_shuffle_metrics_session_row,
                    titles[r.streamId] ?: "#${r.streamId}",
                    (r.sessionShare * 100).toInt()
                )
            }
        }
        body.text = lines.joinToString("\n")
    }

    private fun renderTheta(view: View, theta: DoubleArray) {
        val container = view.findViewById<LinearLayout>(R.id.thetaList)
        container.removeAllViews()
        val inflater = LayoutInflater.from(container.context)
        val rows = theta.indices
            .map { i ->
                val name = FeatureSchema.names[i]
                Triple(name, FeatureSchema.descriptions[name] ?: "", theta[i])
            }
            .sortedByDescending { abs(it.third) }

        for ((name, description, weight) in rows) {
            val row = inflater.inflate(R.layout.item_adaptive_shuffle_weight, container, false)
            row.findViewById<TextView>(R.id.weightDescription).text = description
            val valueView = row.findViewById<TextView>(R.id.weightValue)
            valueView.text = "%+.4f".format(weight)
            valueView.setTextColor(
                when {
                    weight > 0.0 -> positiveColour
                    weight < 0.0 -> negativeColour
                    else -> valueView.textColors.defaultColor
                }
            )
            container.addView(row)
        }
    }

    private fun renderDecisions(view: View, decisions: List<AdaptiveShuffleEngine.RecentDecision>) {
        val container = view.findViewById<LinearLayout>(R.id.decisionList)
        container.removeAllViews()
        if (decisions.isEmpty()) {
            val empty = TextView(container.context)
            empty.text = getString(R.string.adaptive_shuffle_no_events)
            empty.setTextColor(android.graphics.Color.GRAY)
            container.addView(empty)
            return
        }
        val inflater = LayoutInflater.from(container.context)
        for (decision in decisions) {
            val row = inflater.inflate(R.layout.item_adaptive_shuffle_decision, container, false)

            val title = decision.streamTitle ?: getString(R.string.adaptive_shuffle_unknown_title)
            row.findViewById<TextView>(R.id.decisionTitle).text =
                "%s  (#%d)".format(title, decision.streamId)

            row.findViewById<TextView>(R.id.decisionTime).text =
                timestampFormatter.format(java.time.Instant.ofEpochMilli(decision.chosenAt))

            row.findViewById<TextView>(R.id.decisionPred).text =
                "pred %+.3f".format(decision.predictedReward)
            row.findViewById<TextView>(R.id.decisionUnc).text =
                "unc %.3f".format(decision.uncertainty)

            val rewardView = row.findViewById<TextView>(R.id.decisionReward)
            val reward = decision.reward
            if (reward == null) {
                rewardView.text = "R " + getString(R.string.adaptive_shuffle_reward_pending)
                rewardView.setTextColor(rewardView.textColors.defaultColor)
            } else {
                rewardView.text = "R %+.3f".format(reward)
                rewardView.setTextColor(
                    when {
                        reward > 0.0 -> positiveColour
                        reward < 0.0 -> negativeColour
                        else -> rewardView.textColors.defaultColor
                    }
                )
            }

            val chip = row.findViewById<TextView>(R.id.decisionChip)
            if (decision.chooser == Chooser.USER) {
                chip.text = getString(R.string.adaptive_shuffle_user_pick)
                chip.setBackgroundColor(userChipColour)
            } else if (decision.baseline) {
                chip.text = getString(R.string.adaptive_shuffle_random)
                chip.setBackgroundColor(randomChipColour)
            } else if (decision.exploit) {
                chip.text = getString(R.string.adaptive_shuffle_exploit)
                chip.setBackgroundColor(exploitChipColour)
            } else {
                chip.text = getString(R.string.adaptive_shuffle_explore)
                chip.setBackgroundColor(exploreChipColour)
            }

            container.addView(row)
        }
    }
}
