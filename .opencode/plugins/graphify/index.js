import { Plugin } from "@opencode/plugin"
import { existsSync } from "fs"
import { join } from "path"

export default Plugin.define({
  id: "graphify",
  async setup(ctx) {
    let reminded = false

    await ctx.tool.hook("execute.before", (event) => {
      if (reminded) return
      if (event.tool !== "shell" && event.tool !== "bash") return
      if (!existsSync(join(ctx.location.directory, ".graphify", "graph.json"))) return

      const input = event.input
      if (input && typeof input === "object" && "command" in input) {
        input.command =
          'echo "[graphify] knowledge graph at .graphify/. Prefer the graphify MCP tools (query_graph, first_hop_summary, get_neighbors, review_analysis) over grepping raw files. Read .graphify/GRAPH_REPORT.md only for broad architecture context." ; ' +
          input.command
        reminded = true
      }
    })
  },
})
