/*
 * SPDX-FileCopyrightText: 2026 Joshua Jewell <developer@joshuajewell.dev>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.adaptive.ui

import android.graphics.Color
import android.os.Bundle
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
import org.schabi.newpipe.R
import org.schabi.newpipe.adaptive.AdaptivePlayerIntegration
import org.schabi.newpipe.adaptive.AdaptiveShuffleEngine
import org.schabi.newpipe.adaptive.AdaptiveShuffleHolder
import org.schabi.newpipe.adaptive.FeatureSchema

class AdaptiveShuffleDebugFragment : Fragment() {

    private val disposables = CompositeDisposable()
    private val timestampFormatter = DateTimeFormatter
        .ofPattern("HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val positiveColour = Color.parseColor("#2E7D32")
    private val negativeColour = Color.parseColor("#C62828")
    private val exploitChipColour = Color.parseColor("#1565C0")
    private val exploreChipColour = Color.parseColor("#EF6C00")

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
                            Single.fromCallable { integration.engine.rebuildFromPending() }
                                .subscribeOn(Schedulers.io())
                                .observeOn(AndroidSchedulers.mainThread())
                                .subscribe({ count ->
                                    Toast.makeText(
                                        requireContext(),
                                        getString(R.string.adaptive_shuffle_rebuilt, count),
                                        Toast.LENGTH_SHORT
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
            if (decision.exploit) {
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
